package com.custom.chat.config;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class LlmConfig {

    // 1. 关键：接口方法必须通过 @MemoryId 指定哪一个参数是用户唯一标识
    public interface Assistant {
        String chat(@MemoryId String userId, @UserMessage String message);
    }

    // 2. 配置内存提供者
    @Bean
    public ChatMemoryProvider chatMemoryProvider(final DataSource dataSource) {
        // 创建数据库存储器
        final ChatMemoryStore dbStore = new MyDbChatMemoryStore(dataSource);

        // 使用匿名内部类实现 ChatMemoryProvider 接口
        return new ChatMemoryProvider() {
            @Override
            public ChatMemory get(Object userId) { // 关键点：方法名必须是 get
                // 构建并返回对应的 ChatMemory 实例
                return MessageWindowChatMemory.builder()
                        .id(userId)
                        .maxMessages(10)          // 限制每个用户最近的 10 条对话记录
                        .chatMemoryStore(dbStore) // 绑定自定义的数据库存储
                        .build();
            }
        };
    }
    // 3. 构建智能助手，并注入内存提供者
    @Bean
    public Assistant assistant(ChatLanguageModel chatLanguageModel, ChatMemoryProvider chatMemoryProvider) {
        return AiServices.builder(Assistant.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(chatMemoryProvider) // 绑定提供者，不再使用单例 memory
                .build();
    }
}