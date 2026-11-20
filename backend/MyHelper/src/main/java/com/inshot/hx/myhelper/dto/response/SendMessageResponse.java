package com.inshot.hx.myhelper.dto.response;

/** 同步发送完成后的本轮问答，不包含 JPA 实体或模型原始响应。 */
public record SendMessageResponse(MessageResponse userMessage,
                                  MessageResponse assistantMessage) {
}
