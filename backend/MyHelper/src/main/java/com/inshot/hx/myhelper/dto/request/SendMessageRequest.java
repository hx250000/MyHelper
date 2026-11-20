package com.inshot.hx.myhelper.dto.request;

public record SendMessageRequest(String userId, Long conversationId, String content) {
}
