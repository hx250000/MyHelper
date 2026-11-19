package com.inshot.hx.myhelper.service;

import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.common.exception.BusinessException;
import com.inshot.hx.myhelper.dto.request.CreateConversationRequest;
import com.inshot.hx.myhelper.dto.response.ConversationListResponse;
import com.inshot.hx.myhelper.dto.response.ConversationResponse;
import com.inshot.hx.myhelper.entity.Conversation;
import com.inshot.hx.myhelper.repository.ConversationRepository;
import com.inshot.hx.myhelper.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;

    public ConversationService(ConversationRepository conversationRepository,
                               UserRepository userRepository) {
        this.conversationRepository = conversationRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public ConversationResponse createConversation(CreateConversationRequest request) {
        if (request == null || request.getUserId() == null || request.getUserId().isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户 ID 不能为空");
        }
        String userId = request.getUserId().strip();
        if (userId.length() > 16) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户 ID 不能超过 16 个字符");
        }
        // String modelName = normalizeOptionalText(request.getModelName(), 100, "模型名称");

        // 暂未接入登录：显式传入已存在的用户 ID，不创建占位用户，也不接受客户端实体。
  
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "用户不存在"));
        Conversation conversation = new Conversation(user);
        conversation.setTitle("新对话");
        // if (title != null) {
        //     conversation.setTitle(title);
        // }
        // conversation.setModelName(modelName);

        // 在事务内刷新并转换 DTO，确保 ID 和审计时间已生成，不暴露关联 User。
        return ConversationResponse.from(conversationRepository.saveAndFlush(conversation));
    }
    
    @Transactional(readOnly = true)
    public ConversationListResponse getMyConversation(String userId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "用户不存在"));
        List<ConversationResponse> conversations = conversationRepository.findConversationByUser_Id(userId)
                .stream()
                .map(ConversationResponse::from)
                .toList();
        return new ConversationListResponse(user.getUsername(), conversations);
    }

    private String normalizeOptionalText(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    fieldName + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }
}
