package com.inshot.hx.myhelper.service;

import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.common.exception.BusinessException;
import com.inshot.hx.myhelper.dto.response.MessageResponse;
import com.inshot.hx.myhelper.entity.enums.MessageStatus;
import com.inshot.hx.myhelper.tools.ChatContextBudget;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ChatContextBudgetTest {

    @Test
    void promptRetainsTheNewestCompleteTurnsInOrderAtTheExactBudget() {
        var prompt = new AtomicReference<Prompt>();
        var service = recordingService(25, prompt);
        var history = List.of(message(1, "user", "a"), message(2, "assistant", "b"),
                message(3, "user", "c"), message(4, "assistant", "d"),
                message(5, "user", "e"), message(6, "assistant", "f"));

        service.chat(history, "q");

        assertEquals(List.of("c", "d", "e", "f", "q"),
                prompt.get().getInstructions().stream().map(Message::getText).toList());
        assertEquals(6, history.size());
    }

    @Test
    void promptDoesNotSkipTheLatestOversizedTurnToIncludeOlderHistory() {
        var prompt = new AtomicReference<Prompt>();
        var service = recordingService(15, prompt);
        var history = List.of(message(1, "user", "a"), message(2, "assistant", "b"),
                message(3, "user", "c"), message(4, "assistant", " d".repeat(20)));

        service.chat(history, "q");

        assertEquals(List.of("q"), prompt.get().getInstructions().stream().map(Message::getText).toList());
    }

    @Test
    void questionWithoutHistoryIsAlsoValidatedAgainstTheBudget() {
        var model = mock(ChatModel.class);
        var service = new AiService(model, new ChatContextBudget(5));

        var error = assertThrows(BusinessException.class, () -> service.chat_no_history("too many tokens"));

        assertEquals(ResultCode.BAD_REQUEST, error.getResultCode());
        verifyNoInteractions(model);
    }

    private static AiService recordingService(int maxInputTokens, AtomicReference<Prompt> prompt) {
        return new AiService(request -> {
            prompt.set(request);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("reply"))));
        }, new ChatContextBudget(maxInputTokens));
    }

    private static MessageResponse message(long id, String role, String content) {
        return new MessageResponse(id, 10L, role, content, id, MessageStatus.COMPLETED, null, null, null, null);
    }
}
