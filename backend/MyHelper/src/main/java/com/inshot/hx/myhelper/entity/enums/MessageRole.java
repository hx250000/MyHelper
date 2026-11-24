package com.inshot.hx.myhelper.entity.enums;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public enum MessageRole {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
    TOOL("tool");

    private final String value;

    MessageRole(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static MessageRole fromValue(String value) {
        for (MessageRole role : values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        log.error("解析消息角色失败：未知角色，value={}，允许值为 user/assistant/system/tool", value);
        throw new IllegalArgumentException("未知的消息角色：" + value);
    }
}
