package com.inshot.hx.myhelper.service;

import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private final ChatModel chatModel;

    public AiService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** 单轮交互：只发送本次消息，不附带历史记录，也不写入数据库。 */
    public ChatResponse chat(String message) {
        if (message == null || message.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "消息不能为空");
        }
        if (message.length() > 10000) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "消息不能超过 10000 个字符");
        }

        ChatResponse response;
        try {
            response = chatModel.call(new Prompt(message.strip()));
        } catch (RuntimeException exception) {
            // 上游异常可能包含密钥或请求内容，不直接记录或返回它们。
            log.warn("大模型调用失败，异常提示：{}", exception.getMessage());
            throw new BusinessException(ResultCode.AI_UNAVAILABLE,
                    "大模型请求失败，请检查后端的密钥、模型名称及网络连接后重试");
        }

        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                || response.getResult().getOutput().getText().isBlank()) {
            throw new BusinessException(ResultCode.AI_UNAVAILABLE, "大模型未返回文本，请重试");
        }
        // 保留模型、结束原因和用量，由 Controller 转成对外 DTO。
        return response;
    }
}
