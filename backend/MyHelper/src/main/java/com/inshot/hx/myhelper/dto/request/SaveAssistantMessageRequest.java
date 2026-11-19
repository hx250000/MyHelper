package com.inshot.hx.myhelper.dto.request;

import lombok.Getter;
import lombok.Setter;

/** 仅保存 AI 消息；角色、状态和序号仍由服务端决定，不触发模型调用。 */
@Getter
@Setter
public class SaveAssistantMessageRequest {

    private String userId;
    private Long conversationId;
    private String content;
    private String modelName;
    /** 回复的 output/completion tokens，而不是整次调用的 total tokens。 */
    private Integer tokenCount;
}
