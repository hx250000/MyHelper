package com.inshot.hx.myhelper.dto.response;

import java.util.List;

/** 同步发送完成后的本轮问答及实际加入 Prompt 的资料出处。 */
public record SendMessageResponse(MessageResponse userMessage,
                                  MessageResponse assistantMessage,
                                  List<KnowledgeReference> references) {
}
