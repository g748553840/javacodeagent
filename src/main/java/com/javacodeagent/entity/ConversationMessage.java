package com.javacodeagent.entity;

import com.javacodeagent.core.enums.MessageType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
@Data
@Entity
@Table(name = "conversation_messages",
    indexes = {@Index(name = "idx_conv_msg_created", columnList = "conversationId, createdAt")})
public class ConversationMessage {

    @Id
    private String id;

    @Column(name = "conversationId")
    private String conversationId;

    @Enumerated(EnumType.STRING)
    private MessageType type;

    @Column(columnDefinition = "CLOB")
    private String content;

    private String toolCallId;

    @Column(columnDefinition = "CLOB")
    private String toolCallsJson;

    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversationId", insertable = false, updatable = false)
    private Conversation conversation;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
