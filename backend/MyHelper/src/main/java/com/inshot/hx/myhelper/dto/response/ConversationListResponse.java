package com.inshot.hx.myhelper.dto.response;

import java.util.List;

/** 用户名和会话列表，不暴露用户实体及密码哈希。 */
public record ConversationListResponse(String username, List<ConversationResponse> conversations) {
}
