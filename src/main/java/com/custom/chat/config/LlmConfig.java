package com.custom.chat.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.redis.RedisEmbeddingStore;
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
    // 🚀 【核心修改点】将内存向量存储重构为 Redis 向量存储
    @Bean
    public ContentRetriever contentRetriever() {

        // 1. 创建 Redis 向量数据库连接
        EmbeddingStore<TextSegment> redisEmbeddingStore = RedisEmbeddingStore.builder()
                .host("192.168.134.21")                  // 你的 Redis 地址
                .port(6379)                         // 你的 Redis 端口
                .user("default")
                .password("Redis@123!")        // 如果有密码则配置，没有可不写
                .indexName("rag-knowledge-index")   // 在 Redis 中自动创建的索引表名
                .dimension(384)                     // 关键：必须与你下面使用的 Embedding 模型向量维度对齐
                .build();

        // 2. 依然使用本地轻量级向量模型（维度为 384）
        EmbeddingModel embeddingModel = new AllMiniLmL6V2QuantizedEmbeddingModel();

        // 💡 提示：此时你在这个 Bean 初始化时写入的测试数据，会实时通过网络写入物理 Redis 中。
        // 项目重启后，Redis 里的数据也不会丢失！
        TextSegment knowledge1 = TextSegment.from("根据2026年最新规定，上海和北京差旅住宿标准为每天不超过500元。");
        TextSegment knowledge2 = TextSegment.from("公司统一的报销单据提交截止日期为每月的最后一个工作日。");

        // 首次运行后可以把这两行注释掉，因为数据已经在 Redis 库里持久化了
        redisEmbeddingStore.add(embeddingModel.embed(knowledge1).content(), knowledge1);
        redisEmbeddingStore.add(embeddingModel.embed(knowledge2).content(), knowledge2);

        // 3. 构建并返回基于 Redis 驱动的 RAG 检索器
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(redisEmbeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(2) // 每次检索最多召回 2 条最相关的上下文
                .build();
    }
    // 3. 构建智能助手，并注入内存提供者
    @Bean
    public Assistant assistant(ChatLanguageModel chatLanguageModel,
                               ChatMemoryProvider chatMemoryProvider,
                               ContentRetriever contentRetriever) {
        return AiServices.builder(Assistant.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(chatMemoryProvider) // 绑定提供者，不再使用单例 memory
                .contentRetriever(contentRetriever)
                .build();
    }
}