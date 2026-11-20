package com.inshot.hx.myhelper.service;

import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.common.exception.BusinessException;
import com.inshot.hx.myhelper.dto.request.SendMessageRequest;
import com.inshot.hx.myhelper.dto.response.SendMessageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatService {

    private final MessageService messageService;
    private final AiService aiService;

    private long MAXROUND=50;

    private Set<Long> busyConversations = ConcurrentHashMap.newKeySet();

    public ChatService(MessageService messageService, AiService aiService) {
        this.messageService = messageService;
        this.aiService = aiService;
    }

    /** 单轮同步发送；数据库操作各自提交，模型调用期间不持有数据库事务。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SendMessageResponse sendMessage(SendMessageRequest request) {
        var conversationId=request.conversationId();
        if (conversationId == null || conversationId < 1) {
            throw new BusinessException(
                    ResultCode.BAD_REQUEST, "会话 ID 必须为正整数");
        }
        //使用set存放目前未完成的对话
        if (!busyConversations.add(conversationId)) {
            throw new BusinessException(
                    ResultCode.CONFLICT, "本轮对话未完成，请等待对话完成后发送");
        }

        var userContent=request.content();
        var userId=request.userId();
        try{
            //提取用户输入信息、对话历史并发送给AI并接收回复
            var userMessage = messageService.saveUserMessage(userId, conversationId, userContent);
            var conversationHistory=messageService.getModelHistory(userId,conversationId, userMessage.sequence(), MAXROUND);
            var aiResponse = aiService.chat(conversationHistory, request.content());
            var assistantMessage = messageService.saveAssistantMessage(request.userId(),
                    request.conversationId(), aiResponse.reply(), aiResponse.model(),
                    aiResponse.usage() == null ? null : aiResponse.usage().completionTokens());
            return new SendMessageResponse(userMessage, assistantMessage);
        }
        finally {
            busyConversations.remove(conversationId);
        }

    }
}
