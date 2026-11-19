package com.inshot.hx.myhelper.dto.response;

import com.inshot.hx.myhelper.entity.User;

import java.time.LocalDateTime;

/** 仅返回公开资料，绝不序列化 User 实体或密码哈希。 */
public record RegisterResponse(String id, String username, LocalDateTime createdAt,
                               LocalDateTime updatedAt) {

    public static RegisterResponse from(User user) {
        return new RegisterResponse(user.getId(), user.getUsername(),
                user.getCreatedAt(), user.getUpdatedAt());
    }
}
