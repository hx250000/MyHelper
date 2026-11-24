package com.inshot.hx.myhelper.service;

import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.common.exception.BusinessException;
import com.inshot.hx.myhelper.dto.request.SendMessageRequest;
import com.inshot.hx.myhelper.dto.response.SendMessageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ChatService {

    private final MessageService messageService;
    private final AiService aiService;

    private final long MAXROUND=15;

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
            log.warn("发送消息失败：会话 ID 必须为正整数，userId={}, conversationId={}",
                    request.userId(), conversationId);
            throw new BusinessException(
                    ResultCode.BAD_REQUEST, "会话 ID 必须为正整数");
        }
        //使用set存放目前未完成的对话
        if (!busyConversations.add(conversationId)) {
            log.warn("发送消息失败：该会话上一轮回复尚未完成，拒绝并发发送，userId={}, conversationId={}",
                    request.userId(), conversationId);
            throw new BusinessException(
                    ResultCode.CONFLICT, "本轮对话未完成，请等待对话完成后发送");
        }
        log.info("对话{}加入忙列表...",conversationId);

        var userContent=request.content();
        var userId=request.userId();
        log.info("对话：{}，用户：{}发送消息,等待回复生成...", conversationId, userId);

        try{
            //提取用户输入信息、对话历史并发送给AI并接收回复
            var userMessage = messageService.saveUserMessage(userId, conversationId, userContent);
            var conversationHistory=messageService.getModelHistory(userId,conversationId, userMessage.sequence(), MAXROUND);
            var aiResponse = aiService.chat(conversationHistory, request.content());
            var assistantMessage = messageService.saveAssistantMessage(userId,
                    conversationId, userMessage.id(), aiResponse.reply(), aiResponse.model(),
                    aiResponse.usage() == null ? 0 : aiResponse.usage().completionTokens());
            log.info("对话{}已完成AI回复，回复内容{}",conversationId,assistantMessage.content());
            return new SendMessageResponse(userMessage, assistantMessage);
        }
        //try成功与否都会执行，从忙列表中清除当前对话
        finally {
            busyConversations.remove(conversationId);
            log.info("对话{}完成一次回复，移出忙列表",conversationId);
        }

    }
}
