package com.custom.chat.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer; // 新引入的反序列化器
import dev.langchain4j.data.message.ChatMessageSerializer;   // 序列化器
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

public class MyDbChatMemoryStore implements ChatMemoryStore {

    private final JdbcTemplate jdbcTemplate;

    public MyDbChatMemoryStore(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * 从数据库中读取历史消息
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String sql = "SELECT messages_json FROM chat_memory_store WHERE user_id = ?";
        try {
            String json = jdbcTemplate.queryForObject(sql, String.class, memoryId.toString());

            if (json == null || json.trim().isEmpty()) {
                return new ArrayList<ChatMessage>();
            }

            // ⭐ 新版写法：使用 ChatMessageDeserializer.messagesFromJson(json)
            return ChatMessageDeserializer.messagesFromJson(json);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return new ArrayList<ChatMessage>();
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<ChatMessage>();
        }
    }

    /**
     * 将最新的消息列表序列化并“覆盖更新”到数据库
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        // ⭐ 新版写法：使用 ChatMessageSerializer.messagesToJson(messages)
        String jsonMessages = ChatMessageSerializer.messagesToJson(messages);

        String sql = "INSERT INTO chat_memory_store (user_id, messages_json) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE messages_json = VALUES(messages_json)";

        jdbcTemplate.update(sql, memoryId.toString(), jsonMessages);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String sql = "DELETE FROM chat_memory_store WHERE user_id = ?";
        jdbcTemplate.update(sql, memoryId.toString());
    }
}