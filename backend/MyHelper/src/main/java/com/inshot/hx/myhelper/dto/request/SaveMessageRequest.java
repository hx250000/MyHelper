package com.inshot.hx.myhelper.dto.request;

import lombok.Getter;
import lombok.Setter;

/** 仅保存用户消息；身份接入登录后应从服务端认证上下文获取。 */
@Getter
@Setter
public class SaveMessageRequest {

    private String userId;
    private Long conversationId;
    private String content;
}
