package com.inshot.hx.myhelper.dto.request;

import lombok.Getter;
import lombok.Setter;

/** 仅接收创建所需字段；接入登录后，userId 应由服务端认证上下文提供。 */
@Getter
@Setter
public class CreateConversationRequest {

    private String userId;
    private String title;
    private String modelName;
}
