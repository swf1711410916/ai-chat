package com.custom.chat.controller;

import com.custom.chat.config.LlmConfig;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ChatController {

    @Autowired
    private LlmConfig.Assistant assistant;
    @Autowired
    private ContentRetriever contentRetriever; // 注入你的检索器

    /**
     * 多用户隔离对话接口
     * 测试路径：
     * 用户张三: http://localhost:8080/user-chat?userId=zhangsan&prompt=我叫张三
     * 用户李四: http://localhost:8080/user-chat?userId=lisi&prompt=我叫李四
     * 验证记忆: http://localhost:8080/user-chat?userId=zhangsan&prompt=我叫什么名字？
     */
    @GetMapping("/user-chat")
    public String userChat(@RequestParam String userId, @RequestParam String prompt) {
        // 🔍 显式调用检索，打印看看 Redis 吐出来东西没有
        List<Content> contents = contentRetriever.retrieve(Query.from(prompt));

        System.out.println("👉👉 [Debug RAG] 针对问题【" + prompt + "】，Redis 召回了 " + contents.size() + " 条上下文：");
        for (Content content : contents) {
            System.out.println("   -> 召回片段: " + content.textSegment().text());
        }

        if (contents.isEmpty()) {
            System.out.println("⚠️ 警告：Redis 向量库返回空结果！大模型此时只能盲猜。");
        }
        // 将 userId 传入，LangChain4j 会自动路由到该用户对应的内存空间
        return assistant.chat(userId, prompt);
    }
}