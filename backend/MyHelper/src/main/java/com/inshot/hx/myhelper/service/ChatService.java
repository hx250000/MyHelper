package com.inshot.hx.myhelper.service;

import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.common.exception.BusinessException;
import com.inshot.hx.myhelper.dto.request.SendMessageRequest;
import com.inshot.hx.myhelper.dto.response.SendMessageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatService {

    private final MessageService messageService;
    private final AiService aiService;

    private long MAXROUND=50;

    public ChatService(MessageService messageService, AiService aiService) {
        this.messageService = messageService;
        this.aiService = aiService;
    }

    /** 单轮同步发送；数据库操作各自提交，模型调用期间不持有数据库事务。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SendMessageResponse sendMessage(SendMessageRequest request) {
        var userContent=request.content();
        var userId=request.userId();
        var conversationId=request.conversationId();

        var userMessage = messageService.saveUserMessage(userId, conversationId, userContent);

        var conversationHistory=messageService.getModelHistory(userId,conversationId, userMessage.sequence(), MAXROUND);

        var aiResponse = aiService.chat(conversationHistory, request.content());
        var assistantMessage = messageService.saveAssistantMessage(request.userId(),
                request.conversationId(), aiResponse.reply(), aiResponse.model(),
                aiResponse.usage() == null ? null : aiResponse.usage().completionTokens());
        return new SendMessageResponse(userMessage, assistantMessage);
    }
}
