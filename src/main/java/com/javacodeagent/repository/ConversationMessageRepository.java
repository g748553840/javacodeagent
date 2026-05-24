package com.javacodeagent.repository;

import com.javacodeagent.entity.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, String> {
    List<ConversationMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);
}
