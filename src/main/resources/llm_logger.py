import json
import logging
import uuid
from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse
import httpx

# 配置日志：同步输出到终端与 llm.log
logger = logging.getLogger("Anthropic_Ollama_Proxy")
logger.setLevel(logging.INFO)
formatter = logging.Formatter('[%(asctime)s] %(message)s', datefmt='%Y-%m-%d %H:%M:%S')

file_handler = logging.FileHandler("llm.log", encoding="utf-8")
file_handler.setFormatter(formatter)
logger.addHandler(file_handler)

stream_handler = logging.StreamHandler()
stream_handler.setFormatter(formatter)
logger.addHandler(stream_handler)

app = FastAPI()
OLLAMA_BASE_URL = "http://localhost:11434"

# ----------------- 1. 支持 Ollama 原生接口 (/api/chat & /api/show) -----------------

@app.post("/api/show")
async def show_endpoint(request: Request):
    async with httpx.AsyncClient() as client:
        resp = await client.post(f"{OLLAMA_BASE_URL}/api/show", json=await request.json())
        return resp.json()

@app.post("/api/chat")
async def ollama_chat_endpoint(request: Request):
    body = await request.json()

    # 强制注入 32K 上下文配置
    options = body.get("options", {})
    options["num_ctx"] = 32768
    body["options"] = options

    log_prompt_lines = ["\n==================== [PROMPT SENT TO OLLAMA (Native Mode)] ===================="]
    for msg in body.get("messages", []):
        r = msg.get("role", "").upper()
        c = msg.get("content", "")
        log_prompt_lines.append(f"--- [{r}] ---\n{c}\n")
    logger.info("\n".join(log_prompt_lines))

    client = httpx.AsyncClient(timeout=300.0)

    async def stream_generator():
        full_reply = []
        async with client.stream("POST", f"{OLLAMA_BASE_URL}/api/chat", json=body) as response:
            async for line in response.aiter_lines():
                if not line:
                    continue
                try:
                    chunk = json.loads(line)
                    content = chunk.get("message", {}).get("content", "")
                    if content:
                        full_reply.append(content)
                except Exception:
                    pass
                yield line + "\n"

        logger.info(f"\n==================== [LLM RESPONSE] ====================\n{''.join(full_reply)}\n========================================================\n")
        await client.aclose()

    return StreamingResponse(stream_generator(), media_type="application/x-ndjson")

# ----------------- 2. 支持 Anthropic 接口 (/v1/messages) -----------------

@app.post("/v1/messages")
async def messages_endpoint(request: Request):
    body = await request.json()

    raw_model = body.get("model", "")
    target_model = "qwen2.5:3b" if "claude" in raw_model.lower() else raw_model

    anthropic_system = body.get("system", "")
    anthropic_messages = body.get("messages", [])
    anthropic_tools = body.get("tools", [])  # 提取 MCP 工具列表
    temperature = body.get("temperature", 0.7)

    ollama_messages = []

    # 1. 提取 System Prompt
    sys_content = ""
    if anthropic_system:
        if isinstance(anthropic_system, list):
            sys_content = "\n".join([s.get("text", "") for s in anthropic_system if isinstance(s, dict) and s.get("type") == "text"])
        else:
            sys_content = str(anthropic_system)

    # 2. 如果包含了 MCP 工具定义，格式化追加到 System Prompt 末尾
    if anthropic_tools:
        tools_str = json.dumps(anthropic_tools, ensure_ascii=False, indent=2)
        sys_content += f"\n\n[Available MCP Tools]\nYou have access to the following tools:\n{tools_str}\n"

    if sys_content:
        ollama_messages.append({"role": "system", "content": sys_content})

    # 3. 提取对话历史
    for msg in anthropic_messages:
        role = msg.get("role")
        content = msg.get("content")

        if isinstance(content, list):
            text_parts = []
            for c in content:
                if isinstance(c, dict) and c.get("type") == "text":
                    text_parts.append(c.get("text", ""))
            text_content = "\n".join(text_parts)
        else:
            text_content = str(content)

        ollama_messages.append({"role": role, "content": text_content})

    # 4. 组装请求数据包
    ollama_payload = {
        "model": target_model,
        "messages": ollama_messages,
        "stream": True,
        "options": {
            "temperature": temperature,
            "num_ctx": 32768
        }
    }

    # 5. 可读化输出 Prompt 与 System 规则到日志
    log_prompt_lines = ["\n==================== [PROMPT SENT TO LLM (Anthropic Mode)] ===================="]
    for msg in ollama_messages:
        r = msg.get("role", "").upper()
        c = msg.get("content", "")
        log_prompt_lines.append(f"--- [{r}] ---\n{c}\n")
    logger.info("\n".join(log_prompt_lines))

    client = httpx.AsyncClient(timeout=300.0)

    async def stream_generator():
        msg_id = f"msg_{uuid.uuid4().hex[:10]}"
        full_assistant_reply = []

        message_start_event = {
            "type": "message_start",
            "message": {
                "id": msg_id,
                "type": "message",
                "role": "assistant",
                "content": [],
                "model": raw_model,
                "stop_reason": None,
                "stop_sequence": None,
                "usage": {"input_tokens": 0, "output_tokens": 0}
            }
        }
        content_block_start_event = {
            "type": "content_block_start",
            "index": 0,
            "content_block": {"type": "text", "text": ""}
        }

        yield f"event: message_start\ndata: {json.dumps(message_start_event, ensure_ascii=False)}\n\n"
        yield f"event: content_block_start\ndata: {json.dumps(content_block_start_event, ensure_ascii=False)}\n\n"

        async with client.stream("POST", f"{OLLAMA_BASE_URL}/api/chat", json=ollama_payload) as response:
            async for line in response.aiter_lines():
                if not line:
                    continue
                try:
                    chunk = json.loads(line)
                    content = chunk.get("message", {}).get("content", "")

                    if content:
                        full_assistant_reply.append(content)
                        delta_event = {
                            "type": "content_block_delta",
                            "index": 0,
                            "delta": {"type": "text_delta", "text": content}
                        }
                        yield f"event: content_block_delta\ndata: {json.dumps(delta_event, ensure_ascii=False)}\n\n"
                except Exception as e:
                    logger.error(f"Error parsing line: {e}")
                    continue

        content_block_stop_event = {"type": "content_block_stop", "index": 0}
        message_delta_event = {
            "type": "message_delta",
            "delta": {"stop_reason": "end_turn", "stop_sequence": None},
            "usage": {"output_tokens": 0}
        }
        message_stop_event = {"type": "message_stop"}

        yield f"event: content_block_stop\ndata: {json.dumps(content_block_stop_event, ensure_ascii=False)}\n\n"
        yield f"event: message_delta\ndata: {json.dumps(message_delta_event, ensure_ascii=False)}\n\n"
        yield f"event: message_stop\ndata: {json.dumps(message_stop_event, ensure_ascii=False)}\n\n"

        reply_str = ''.join(full_assistant_reply)
        logger.info(f"\n==================== [LLM RESPONSE TEXT/XML] ====================\n{reply_str}\n========================================================\n")
        await client.aclose()

    return StreamingResponse(stream_generator(), media_type="text/event-stream")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)