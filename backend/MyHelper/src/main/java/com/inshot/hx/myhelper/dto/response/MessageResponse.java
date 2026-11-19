package com.inshot.hx.myhelper.dto.response;

import com.inshot.hx.myhelper.entity.Message;
import com.inshot.hx.myhelper.entity.enums.MessageStatus;

import java.time.LocalDateTime;

/** 消息的对外视图，不序列化会话、用户等 JPA 关联。 */
public record MessageResponse(Long id, Long conversationId, String role, String content,
                              Long sequence, MessageStatus status, String modelName,
                              Integer tokenCount, LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static MessageResponse from(Message message) {
        return new MessageResponse(message.getId(), message.getConversation().getId(),
                message.getRole().getValue(), message.getContent(), message.getSequence(),
                message.getStatus(), message.getModelName(), message.getTokenCount(),
                message.getCreatedAt(), message.getUpdatedAt());
    }
}
