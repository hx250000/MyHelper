package com.inshot.hx.myhelper.service;

import com.inshot.hx.myhelper.dto.request.SendMessageRequest;
import com.inshot.hx.myhelper.dto.response.AiResponse;
import com.inshot.hx.myhelper.dto.response.MessageResponse;
import com.inshot.hx.myhelper.entity.enums.MessageStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    @Test
    void savesUserMessageWithParametersBeforeLoadingHistoryAndGeneratingReply() {
        var messageService = mock(MessageService.class);
        var aiService = mock(AiService.class);
        var service = new ChatService(messageService, aiService);
        var userMessage = new MessageResponse(1L, 10L, "user", "  你好  ", 1L,
                MessageStatus.COMPLETED, null, null, null, null);
        var assistantMessage = new MessageResponse(2L, 10L, "assistant", "你好！", 2L,
                MessageStatus.COMPLETED, "test-model", 8, null, null);
        List<MessageResponse> history = List.of();
        var aiResponse = new AiResponse("你好！", null, "test-model", "stop",
                new AiResponse.TokenUsage(3, 8, 11));
        when(messageService.saveUserMessage("user-1", 10L, "  你好  ")).thenReturn(userMessage);
        when(messageService.getModelHistory("user-1", 10L, 1L, 50L)).thenReturn(history);
        when(aiService.chat(history, "  你好  ")).thenReturn(aiResponse);
        when(messageService.saveAssistantMessage("user-1", 10L, "你好！", "test-model", 8))
                .thenReturn(assistantMessage);

        var result = service.sendMessage(new SendMessageRequest("user-1", 10L, "  你好  "));

        assertSame(userMessage, result.userMessage());
        assertSame(assistantMessage, result.assistantMessage());
        var order = inOrder(messageService, aiService);
        order.verify(messageService).saveUserMessage("user-1", 10L, "  你好  ");
        order.verify(messageService).getModelHistory("user-1", 10L, 1L, 50L);
        order.verify(aiService).chat(history, "  你好  ");
        order.verify(messageService).saveAssistantMessage("user-1", 10L, "你好！", "test-model", 8);
        order.verifyNoMoreInteractions();
    }
}
