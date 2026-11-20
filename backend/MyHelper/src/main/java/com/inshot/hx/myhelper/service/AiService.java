package com.inshot.hx.myhelper.service;

import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.common.exception.BusinessException;
import com.inshot.hx.myhelper.dto.response.AiResponse;
import com.inshot.hx.myhelper.dto.response.MessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import com.inshot.hx.myhelper.dto.response.MessageResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private final ChatModel chatModel;

    public AiService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public AiResponse chat_no_history(String message){
        return chat(new ArrayList<>(), message);
    }

    /** 发送本次消息，并附带历史记录，接收回复 */
    public AiResponse chat(List<MessageResponse> history, String message) {
        if (message == null || message.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "消息不能为空");
        }
        if (message.length() > 10000) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "消息不能超过 10000 个字符");
        }

        //加入历史对话记录
        List<Message> messages = new ArrayList<>();
        for (var item : history) {
            Message historyMessage = switch (item.role()) {
                case "user" -> new UserMessage(item.content());
                case "assistant" -> new AssistantMessage(item.content());
                default -> throw new IllegalArgumentException(
                        "不支持的历史消息角色：" + item.role());
            };
            messages.add(historyMessage);
        }
        // 历史不包含当前问题，在这里追加一次
        messages.add(new UserMessage(message));

        ChatResponse response;
        try {
            response = chatModel.call(new Prompt(messages));
        } catch (RuntimeException exception) {
            // 上游异常可能包含密钥或请求内容，不直接记录或返回它们。
            log.warn("大模型调用失败，异常类型：{}", exception.getClass().getSimpleName());
            throw new BusinessException(ResultCode.AI_UNAVAILABLE,
                    "大模型请求失败，请检查后端的密钥、模型名称及网络连接后重试");
        }

        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                || response.getResult().getOutput().getText().isBlank()) {
            throw new BusinessException(ResultCode.AI_UNAVAILABLE, "大模型未返回文本，请重试");
        }
        // 保留模型、结束原因和用量，统一转成业务 DTO。
        return AiResponse.from(response);
    }
}
