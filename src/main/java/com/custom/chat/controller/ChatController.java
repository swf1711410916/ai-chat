package com.custom.chat.controller;

import com.custom.chat.config.LlmConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    @Autowired
    private LlmConfig.Assistant assistant;

    /**
     * 多用户隔离对话接口
     * 测试路径：
     * 用户张三: http://localhost:8080/user-chat?userId=zhangsan&prompt=我叫张三
     * 用户李四: http://localhost:8080/user-chat?userId=lisi&prompt=我叫李四
     * 验证记忆: http://localhost:8080/user-chat?userId=zhangsan&prompt=我叫什么名字？
     */
    @GetMapping("/user-chat")
    public String userChat(@RequestParam String userId, @RequestParam String prompt) {
        // 将 userId 传入，LangChain4j 会自动路由到该用户对应的内存空间
        return assistant.chat(userId, prompt);
    }
}