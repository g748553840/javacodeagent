package com.javacodeagent.core.conversation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javacodeagent.core.enums.MessageType;
import com.javacodeagent.core.model.Message;
import com.javacodeagent.core.model.ToolCall;
import com.javacodeagent.entity.ConversationMessage;
import com.javacodeagent.repository.ConversationMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 对话消息 JPA 持久化服务。
 * ConversationManager 通过此服务在每次对话后保存新消息，
 * 并在对话开始时从数据库加载历史记录，实现跨重启的消息持久化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessagePersistenceService {

    private final ConversationMessageRepository repository;
    private final ObjectMapper objectMapper;

    /** 从数据库加载某个 conversationId 的全部消息历史，按时间升序。 */
    public List<Message> loadHistory(String conversationId) {
        return repository.findByConversationIdOrderByCreatedAtAsc(conversationId)
            .stream()
            .map(this::toMessage)
            .toList();
    }

    /**
     * 批量持久化一组消息（只保存 USER / ASSISTANT / TOOL_RESULT）。
     * 异常必须向外抛出而非在此吞掉：@Transactional 只有在异常传播出方法时才会回滚，
     * 否则方法正常返回会导致已保存的部分消息被提交，破坏"批次内要么全存要么全不存"的原子性。
     * 调用方需要自行决定持久化失败时是否要中断整个对话流程。
     */
    @Transactional
    public void saveMessages(String conversationId, List<Message> messages) {
        for (Message msg : messages) {
            repository.save(toEntity(conversationId, msg));
        }
    }

    // -------------------------------------------------------------------------

    private ConversationMessage toEntity(String conversationId, Message msg) {
        ConversationMessage entity = new ConversationMessage();
        entity.setId(UUID.randomUUID().toString());
        entity.setConversationId(conversationId);
        entity.setType(msg.getType());
        entity.setContent(msg.getContent());
        entity.setToolCallId(msg.getToolCallId());

        if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            try {
                entity.setToolCallsJson(objectMapper.writeValueAsString(msg.getToolCalls()));
            } catch (Exception e) {
                log.warn("Failed to serialize tool calls", e);
            }
        }
        return entity;
    }

    private Message toMessage(ConversationMessage entity) {
        Message.MessageBuilder builder = Message.builder()
            .type(entity.getType())
            .content(entity.getContent())
            .toolCallId(entity.getToolCallId());

        if (entity.getToolCallsJson() != null && !entity.getToolCallsJson().isEmpty()) {
            try {
                List<ToolCall> calls = objectMapper.readValue(
                    entity.getToolCallsJson(), new TypeReference<>() {});
                builder.toolCalls(calls);
            } catch (Exception e) {
                log.warn("Failed to deserialize tool calls", e);
            }
        }
        return builder.build();
    }
}
