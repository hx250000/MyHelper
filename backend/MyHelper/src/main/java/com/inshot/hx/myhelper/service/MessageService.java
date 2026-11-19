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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;

    public MessageService(MessageRepository messageRepository,
                          ConversationRepository conversationRepository) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
    }

    /** 查询用于前端展示的完整历史，保留每条消息的实际状态。 */
    @Transactional(readOnly = true)
    public List<MessageResponse> getHistory(String userId, Long conversationId) {
        validateConversationReference(userId, conversationId);
        var conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "会话不存在"));
        checkConversationAccess(conversation, userId);
        return messageRepository.findByConversation_IdOrderBySequenceAsc(conversationId)
                .stream()
                .map(MessageResponse::from)
                .toList();
    }

    /** 仅入库，不调用模型，也不创建生成任务。 */
    @Transactional
    public MessageResponse saveUserMessage(SaveMessageRequest request) {
        if (request == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求不能为空");
        }
        validateContent(request.getContent());
        if (request.getContent().length() > 10000) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "消息不能超过 10000 个字符");
        }
        return saveCompletedMessage(request.getUserId(), request.getConversationId(),
                request.getContent(), MessageRole.USER, null, null);
    }

    /** 保存 AI 消息，不调用模型；角色固定为 assistant。 */
    @Transactional
    public MessageResponse saveAssistantMessage(String userId, Long conversationId, String content,
                                                String modelName, Integer tokenCount) {
        validateContent(content);
        if (modelName != null && modelName.length() > 100) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "模型名称不能超过 100 个字符");
        }
        if (tokenCount != null && tokenCount < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "Token 数量不能为负数");
        }
        return saveCompletedMessage(userId, conversationId, content,
                MessageRole.ASSISTANT, modelName, tokenCount);
    }

    private MessageResponse saveCompletedMessage(String userId, Long conversationId, String content,
                                                 MessageRole role, String modelName, Integer tokenCount) {
        validateConversationReference(userId, conversationId);

        // 必须先锁会话、再查询最大序号，且锁与消息保存处于同一个短事务。
        var conversation = conversationRepository.findByIdForUpdate(conversationId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "会话不存在"));
        checkConversationAccess(conversation, userId);
        long maxSequence = messageRepository.findMaxSequence(conversationId);
        if (maxSequence == Long.MAX_VALUE) {
            throw new BusinessException(ResultCode.CONFLICT, "会话消息序号已达上限");
        }
        Message message = new Message(conversation, role, maxSequence + 1, content);
        message.setStatus(MessageStatus.COMPLETED);
        message.setModelName(modelName);
        message.setTokenCount(tokenCount);
        conversation.markMessageActivity();
        return MessageResponse.from(messageRepository.saveAndFlush(message));
    }

    private void validateConversationReference(String userId, Long conversationId) {
        if (userId == null || userId.isBlank() || userId.strip().length() > 16) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户 ID 必须为 1 到 16 个字符");
        }
        if (conversationId == null || conversationId < 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "会话 ID 必须为正整数");
        }
    }

    private void checkConversationAccess(Conversation conversation, String userId) {
        if (!conversation.getUser().getId().equals(userId.strip())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该会话");
        }
        if (conversation.isDeleted()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会话已删除");
        }
    }

    private void validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "消息不能为空");
        }
        // 保留原始空白和换行，不破坏用户文本或模型生成的代码块。
    }
}
