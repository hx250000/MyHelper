package com.inshot.hx.myhelper.service;

import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.common.exception.BusinessException;
import com.inshot.hx.myhelper.dto.request.SaveMessageRequest;
import com.inshot.hx.myhelper.dto.response.MessageResponse;
import com.inshot.hx.myhelper.entity.Conversation;
import com.inshot.hx.myhelper.entity.Message;
import com.inshot.hx.myhelper.entity.enums.MessageRole;
import com.inshot.hx.myhelper.entity.enums.MessageStatus;
import com.inshot.hx.myhelper.repository.ConversationRepository;
import com.inshot.hx.myhelper.repository.MessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class MessageService {

    private static final long MAX_MODEL_HISTORY_ROUNDS = 100;

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;

    public MessageService(MessageRepository messageRepository,
                          ConversationRepository conversationRepository) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
    }

    /**
     * 按回复关联读取本次用户消息之前的已完成问答，最多检查 100 个候选轮次。
     * 排除内容空白的轮次，取最近 maxRounds 轮，再按提问顺序从旧到新返回。
     */
    @Transactional(readOnly = true)
    public List<MessageResponse> getModelHistory(String userId, long conversationId,
                                                long beforeSequence, long maxRounds) {
        validateConversationReference(userId, conversationId);
        if (beforeSequence < 1) {
            log.warn("查询模型历史失败：截止序号必须为正整数，userId={}, conversationId={}, beforeSequence={}",
                    userId, conversationId, beforeSequence);
            throw new BusinessException(ResultCode.BAD_REQUEST, "历史消息截止序号必须为正整数");
        }
        if (maxRounds < 1 || maxRounds > MAX_MODEL_HISTORY_ROUNDS) {
            log.warn("查询模型历史失败：历史轮数必须为 1 到 100，userId={}, conversationId={}, maxRounds={}",
                    userId, conversationId, maxRounds);
            throw new BusinessException(ResultCode.BAD_REQUEST, "历史轮数必须为 1 到 100");
        }
        var conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> {
                    log.warn("查询模型历史失败：会话不存在，userId={}, conversationId={}", userId, conversationId);
                    return new BusinessException(ResultCode.NOT_FOUND, "会话不存在");
                });
        checkConversationAccess(conversation, userId);

        var replies = messageRepository.findModelHistoryReplies(conversationId, beforeSequence,
                MessageRole.USER, MessageRole.ASSISTANT, MessageStatus.COMPLETED,
                PageRequest.of(0, (int) MAX_MODEL_HISTORY_ROUNDS));
        var recentRounds = replies.stream()
                .filter(reply -> !reply.getContent().isBlank() && !reply.getReplyTo().getContent().isBlank())
                .limit(maxRounds)
                .toList();
        var history = new ArrayList<MessageResponse>(recentRounds.size() * 2);
        for (var reply : recentRounds.reversed()) {
            history.add(MessageResponse.from(reply.getReplyTo()));
            history.add(MessageResponse.from(reply));
        }
        return List.copyOf(history);
    }

    /** 查询用于前端展示的完整历史，保留每条消息的实际状态。 */
    @Transactional(readOnly = true)
    public List<MessageResponse> getHistoryForUser(String userId, Long conversationId) {
        validateConversationReference(userId, conversationId);
        var conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> {
                    log.warn("查询展示历史失败：会话不存在，userId={}, conversationId={}", userId, conversationId);
                    return new BusinessException(ResultCode.NOT_FOUND, "会话不存在");
                });
        checkConversationAccess(conversation, userId);
        return messageRepository.findByConversation_IdOrderBySequenceAsc(conversationId)
                .stream()
                .map(MessageResponse::from)
                .toList();
    }

    /** 兼容独立保存请求；内部聊天流程直接传入保存所需参数。 */
    @Transactional
    public MessageResponse saveUserMessage(SaveMessageRequest request) {
        if (request == null) {
            log.warn("保存用户消息失败：请求对象为 null");
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求不能为空");
        }
        return saveUserMessage(request.getUserId(), request.getConversationId(), request.getContent());
    }

    /** 仅入库，不调用模型，也不创建生成任务。 */
    @Transactional
    public MessageResponse saveUserMessage(String userId, Long conversationId, String content) {
        validateContent(content);
        if (content.length() > 10000) {
            log.warn("保存用户消息失败：消息超过 10000 个字符，userId={}, conversationId={}, contentLength={}",
                    userId, conversationId, content.length());
            throw new BusinessException(ResultCode.BAD_REQUEST, "消息不能超过 10000 个字符");
        }
        return saveCompletedMessage(userId, conversationId, content, MessageRole.USER, null, null, null);
    }

    /** 保存 AI 消息，不调用模型；关联同一会话中已保存的用户消息。 */
    @Transactional
    public MessageResponse saveAssistantMessage(String userId, Long conversationId, Long replyToMessageId, String content,
                                                String modelName, Integer tokenCount) {
        validateContent(content);
        if (modelName != null && modelName.length() > 100) {
            log.warn("保存 AI 消息失败：模型名称超过 100 个字符，userId={}, conversationId={}, modelNameLength={}",
                    userId, conversationId, modelName.length());
            throw new BusinessException(ResultCode.BAD_REQUEST, "模型名称不能超过 100 个字符");
        }
        if (tokenCount != null && tokenCount < 0) {
            log.warn("保存 AI 消息失败：Token 数量不能为负数，userId={}, conversationId={}, tokenCount={}",
                    userId, conversationId, tokenCount);
            throw new BusinessException(ResultCode.BAD_REQUEST, "Token 数量不能为负数");
        }
        return saveCompletedMessage(userId, conversationId, content,
                MessageRole.ASSISTANT, replyToMessageId, modelName, tokenCount);
    }

    private MessageResponse saveCompletedMessage(String userId, Long conversationId, String content,
                                                 MessageRole role, Long replyToMessageId, String modelName,
                                                 Integer tokenCount) {
        validateConversationReference(userId, conversationId);

        // 必须先锁会话、再查询最大序号，且锁与消息保存处于同一个短事务。
        var conversation = conversationRepository.findByIdForUpdate(conversationId)
                .orElseThrow(() -> {
                    log.warn("保存消息失败：会话不存在，userId={}, conversationId={}, role={}",
                            userId, conversationId, role);
                    return new BusinessException(ResultCode.NOT_FOUND, "会话不存在");
                });
        checkConversationAccess(conversation, userId);
        Message question = role == MessageRole.ASSISTANT ? findQuestionForReply(conversationId, replyToMessageId) : null;
        long maxSequence = messageRepository.findMaxSequence(conversationId);
        if (maxSequence == Long.MAX_VALUE) {
            log.warn("保存消息失败：会话消息序号已达上限，userId={}, conversationId={}, maxSequence={}",
                    userId, conversationId, maxSequence);
            throw new BusinessException(ResultCode.CONFLICT, "会话消息序号已达上限");
        }
        Message message = new Message(conversation, role, maxSequence + 1, content);
        message.setReplyTo(question);
        message.setStatus(MessageStatus.COMPLETED);
        message.setModelName(modelName);
        message.setTokenCount(tokenCount);
        conversation.markMessageActivity();
        return MessageResponse.from(messageRepository.saveAndFlush(message));
    }

    // 寻找回复关联的用户消息
    private Message findQuestionForReply(Long conversationId, Long replyToMessageId) {
        if (replyToMessageId == null || replyToMessageId < 1) {
            log.warn("保存 AI 消息失败：关联消息 ID 必须为正整数，conversationId={}, replyToMessageId={}",
                    conversationId, replyToMessageId);
            throw new BusinessException(ResultCode.BAD_REQUEST, "回复必须关联一条用户消息");
        }
        Message question = messageRepository.findByIdAndConversation_IdAndRole(
                replyToMessageId, conversationId, MessageRole.USER).orElseThrow(() -> {
                    log.warn("保存 AI 消息失败：同会话用户消息不存在，conversationId={}, replyToMessageId={}",
                            conversationId, replyToMessageId);
                    return new BusinessException(ResultCode.BAD_REQUEST, "回复必须关联同一会话的用户消息");
                });
        if (messageRepository.existsByReplyTo_Id(replyToMessageId)) {
            log.warn("保存 AI 消息失败：用户消息已有回复，conversationId={}, replyToMessageId={}",
                    conversationId, replyToMessageId);
            throw new BusinessException(ResultCode.CONFLICT, "该用户消息已有回复");
        }
        return question;
    }

    private void validateConversationReference(String userId, Long conversationId) {
        if (userId == null || userId.isBlank() || userId.strip().length() > 16) {
            log.warn("消息操作失败：用户 ID 为空、空白或超过 16 个字符，conversationId={}, userIdLength={}",
                    conversationId, userId == null ? null : userId.strip().length());
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户 ID 必须为 1 到 16 个字符");
        }
        if (conversationId == null || conversationId < 1) {
            log.warn("消息操作失败：会话 ID 必须为正整数，userId={}, conversationId={}", userId, conversationId);
            throw new BusinessException(ResultCode.BAD_REQUEST, "会话 ID 必须为正整数");
        }
    }

    private void checkConversationAccess(Conversation conversation, String userId) {
        if (!conversation.getUser().getId().equals(userId.strip())) {
            log.warn("消息操作失败：当前用户不是会话所有者，userId={}, conversationId={}",
                    userId, conversation.getId());
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该会话");
        }
        if (conversation.isDeleted()) {
            log.warn("消息操作失败：会话已被软删除，userId={}, conversationId={}", userId, conversation.getId());
            throw new BusinessException(ResultCode.NOT_FOUND, "会话已删除");
        }
    }

    private void validateContent(String content) {
        if (content == null || content.isBlank()) {
            log.warn("消息内容校验失败：内容为 null 或空白，contentLength={}", content == null ? null : content.length());
            throw new BusinessException(ResultCode.BAD_REQUEST, "消息不能为空");
        }
        // 保留原始空白和换行，不破坏用户文本或模型生成的代码块。
    }
}
