package com.inshot.hx.myhelper.dto.response;

import com.inshot.hx.myhelper.entity.Conversation;

import java.time.LocalDateTime;

/** 不序列化 JPA 实体和关联用户，避免懒加载及密码哈希泄露。 */
public record ConversationResponse(Long id, String userId, String title, String modelName,
                                   LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(conversation.getId(), conversation.getUser().getId(),
                conversation.getTitle(), conversation.getModelName(),
                conversation.getCreatedAt(), conversation.getUpdatedAt());
    }
}
