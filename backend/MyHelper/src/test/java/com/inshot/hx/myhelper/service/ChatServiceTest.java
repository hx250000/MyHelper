package com.inshot.hx.myhelper.service;

import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.common.exception.BusinessException;
import com.inshot.hx.myhelper.dto.request.SendMessageRequest;
import com.inshot.hx.myhelper.dto.response.AiResponse;
import com.inshot.hx.myhelper.dto.response.MessageResponse;
import com.inshot.hx.myhelper.entity.enums.MessageStatus;
import com.inshot.hx.myhelper.tools.ChatContextBudget;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    @Test
    void savesUserMessageWithParametersBeforeLoadingHistoryAndGeneratingReply() {
        var messageService = mock(MessageService.class);
        var aiService = mock(AiService.class);
        var service = new ChatService(messageService, aiService);
        var userMessage = new MessageResponse(41L, 10L, "user", "  你好  ", 1L,
                MessageStatus.COMPLETED, null, null, null, null);
        var assistantMessage = new MessageResponse(42L, 10L, "assistant", "你好！", 2L,
                MessageStatus.COMPLETED, "test-model", 8, null, null);
        List<MessageResponse> history = List.of();
        var aiResponse = new AiResponse("你好！", null, "test-model", "stop",
                new AiResponse.TokenUsage(3, 8, 11));
        when(messageService.saveUserMessage("user-1", 10L, "  你好  ")).thenReturn(userMessage);
        when(messageService.getModelHistory("user-1", 10L, 1L, 15L)).thenReturn(history);
        when(aiService.chat(history, "  你好  ")).thenReturn(aiResponse);
        when(messageService.saveAssistantMessage("user-1", 10L, 41L, "你好！", "test-model", 8))
                .thenReturn(assistantMessage);

        var result = service.sendMessage(new SendMessageRequest("user-1", 10L, "  你好  "));

        assertSame(userMessage, result.userMessage());
        assertSame(assistantMessage, result.assistantMessage());
        var order = inOrder(messageService, aiService);
        order.verify(aiService).validateQuestion("  你好  ");
        order.verify(messageService).saveUserMessage("user-1", 10L, "  你好  ");
        order.verify(messageService).getModelHistory("user-1", 10L, 1L, 15L);
        order.verify(aiService).chat(history, "  你好  ");
        order.verify(messageService).saveAssistantMessage("user-1", 10L, 41L, "你好！", "test-model", 8);
        order.verifyNoMoreInteractions();
    }

    @Test
    void rejectsQuestionOverBudgetBeforeAnyPersistenceOrModelCall() {
        var messages = mock(MessageService.class);
        var model = mock(ChatModel.class);
        var service = new ChatService(messages, new AiService(model, new ChatContextBudget(5)));

        var error = assertThrows(BusinessException.class,
                () -> service.sendMessage(new SendMessageRequest("user-1", 10L, "too many tokens")));

        assertEquals(ResultCode.BAD_REQUEST, error.getResultCode());
        verifyNoInteractions(messages, model);
    }
}
