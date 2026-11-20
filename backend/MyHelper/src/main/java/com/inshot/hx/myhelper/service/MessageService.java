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
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

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
     * 一次读取本次用户消息之前最近 200 条原始消息，在内存中选取完整问答。
     * 返回最近 maxRounds 轮，按 sequence 升序排列；不足时直接返回，不继续补查。
     * maxRounds 为轮数（1～100），每轮两条，空白内容和未完成轮次均不进入上下文。
     * 当前数据模型没有问答关联 ID，只能按相邻消息配对，调用方必须保证同会话串行发送。
     */
    @Transactional(readOnly = true)
    public List<MessageResponse> getModelHistory(String userId, long conversationId,
                                                long beforeSequence, long maxRounds) {
        validateConversationReference(userId, conversationId);
        if (beforeSequence < 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "历史消息截止序号必须为正整数");
        }
        if (maxRounds < 1 || maxRounds > MAX_MODEL_HISTORY_ROUNDS) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "历史轮数必须为 1 到 100");
        }
        var conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "会话不存在"));
        checkConversationAccess(conversation, userId);

        var messages = messageRepository.findByConversation_IdAndSequenceLessThanOrderBySequenceDesc(
                conversationId, beforeSequence, PageRequest.of(0, (int) MAX_MODEL_HISTORY_ROUNDS * 2));
        var ordered = messages.reversed();
        var history = new ArrayList<MessageResponse>();
        for (int i = 0; i + 1 < ordered.size(); i++) {
            var userMessage = ordered.get(i);
            var assistantMessage = ordered.get(i + 1);
            // 必须在原始序列中相邻，不能先删除失败消息再配对。
            if (isCompletedMessage(userMessage, MessageRole.USER)
                    && isCompletedMessage(assistantMessage, MessageRole.ASSISTANT)) {
                history.add(MessageResponse.from(userMessage));
                history.add(MessageResponse.from(assistantMessage));
            }
        }
        int start = Math.max(0, history.size() - (int) maxRounds * 2);
        return List.copyOf(history.subList(start, history.size()));
    }

    private boolean isCompletedMessage(Message message, MessageRole role) {
        return message.getRole() == role
                && message.getStatus() == MessageStatus.COMPLETED
                && !message.getContent().isBlank();
    }

    /** 查询用于前端展示的完整历史，保留每条消息的实际状态。 */
    @Transactional(readOnly = true)
    public List<MessageResponse> getHistoryForUser(String userId, Long conversationId) {
        validateConversationReference(userId, conversationId);
        var conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "会话不存在"));
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
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求不能为空");
        }
        return saveUserMessage(request.getUserId(), request.getConversationId(), request.getContent());
    }

    /** 仅入库，不调用模型，也不创建生成任务。 */
    @Transactional
    public MessageResponse saveUserMessage(String userId, Long conversationId, String content) {
        validateContent(content);
        if (content.length() > 10000) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "消息不能超过 10000 个字符");
        }
        return saveCompletedMessage(userId, conversationId, content, MessageRole.USER, null, null);
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
