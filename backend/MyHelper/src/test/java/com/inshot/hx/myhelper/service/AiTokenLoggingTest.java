package com.inshot.hx.myhelper.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.inshot.hx.myhelper.common.exception.BusinessException;
import com.inshot.hx.myhelper.dto.response.MessageResponse;
import com.inshot.hx.myhelper.entity.enums.MessageStatus;
import com.inshot.hx.myhelper.tools.ChatContextBudget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiTokenLoggingTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(AiService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
    }

    @Test
    void logsOnlyIncludedContextAndKeepsProviderUsageSeparateFromEstimates() {
        var budget = new ChatContextBudget(256);
        var question = "PRIVATE_QUESTION";
        var history = List.of(message(1L, "user", " old".repeat(300)), message(2L, "assistant", "old reply"),
                message(3L, "user", "PRIVATE_HISTORY"), message(4L, "assistant", "recent reply"));
        var documents = List.of(new Document(" large".repeat(300)),
                new Document("PRIVATE_DOCUMENT", Map.of("source", "private-source.md")));
        var sentPrompt = new AtomicReference<Prompt>();
        var service = new AiService(prompt -> {
            sentPrompt.set(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("PRIVATE_REPLY"))), usageMetadata());
        }, budget);

        service.chat(history, question, documents);

        var messages = sentPrompt.get().getInstructions();
        assertEquals(3, messages.size());
        assertTrue(messages.getLast().getText().contains("PRIVATE_DOCUMENT"));
        int questionTokens = budget.estimateMessage(question);
        int historyTokens = budget.estimateMessage(history.get(2).content()) + budget.estimateMessage(history.get(3).content());
        int knowledgeTokens = budget.estimateMessage(messages.getLast().getText()) - questionTokens;

        assertUsageFields("status=success", "model=test-model", "promptTokens=61", "completionTokens=9", "totalTokens=77",
                "estimatedInputTokens=" + (questionTokens + historyTokens + knowledgeTokens),
                "estimatedQuestionTokens=" + questionTokens, "estimatedHistoryTokens=" + historyTokens,
                "estimatedKnowledgeTokens=" + knowledgeTokens, "candidateHistoryMessages=4", "selectedHistoryMessages=2",
                "retrievedChunks=2", "includedChunks=1");
        assertNoLoggedText("PRIVATE_QUESTION", "PRIVATE_HISTORY", "PRIVATE_DOCUMENT", "private-source.md", "PRIVATE_REPLY");
    }

    @Test
    void logsMissingProviderUsageAsNullForSuccessfulSingleTurn() {
        var service = new AiService(prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("reply")))),
                new ChatContextBudget(256));

        service.chatWithoutHistory("question");

        assertUsageFields("status=success", "promptTokens=null", "completionTokens=null", "totalTokens=null",
                "estimatedHistoryTokens=0", "estimatedKnowledgeTokens=0", "candidateHistoryMessages=0", "selectedHistoryMessages=0",
                "retrievedChunks=0", "includedChunks=0");
    }

    @Test
    void retainsProviderUsageEvenWhenAResponseHasNoText() {
        var service = new AiService(prompt -> new ChatResponse(List.of(), usageMetadata()), new ChatContextBudget(256));

        assertThrows(BusinessException.class, () -> service.chatWithoutHistory("question"));

        assertUsageFields("status=invalid_response", "promptTokens=61", "completionTokens=9", "totalTokens=77");
    }

    @Test
    void logsFailedCallsWithoutLeakingRequestOrExceptionText() {
        var service = new AiService(prompt -> { throw new IllegalStateException("PRIVATE_UPSTREAM_ERROR"); },
                new ChatContextBudget(256));

        assertThrows(BusinessException.class, () -> service.chatWithoutHistory("PRIVATE_QUESTION"));

        assertUsageFields("status=upstream_error", "promptTokens=null", "completionTokens=null", "totalTokens=null");
        assertNoLoggedText("PRIVATE_UPSTREAM_ERROR", "PRIVATE_QUESTION");
    }

    @Test
    void doesNotLogUsageWhenAnOversizedQuestionIsRejectedBeforeCallingTheModel() {
        var service = new AiService(prompt -> { throw new AssertionError("unexpected model call"); },
                new ChatContextBudget(5));

        assertThrows(BusinessException.class, () -> service.chatWithoutHistory("too many tokens"));

        assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().startsWith("chat_token_usage ")));
    }

    private void assertUsageFields(String... fields) {
        var events = appender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("chat_token_usage ")).toList();
        assertEquals(1, events.size());
        assertEquals(Level.INFO, events.getFirst().getLevel());
        var formatted = events.getFirst().getFormattedMessage();
        var loggedFields = List.of(formatted.split(" "));
        for (String field : fields) {
            assertTrue(loggedFields.contains(field), () -> "Missing field " + field + " in " + formatted);
        }
    }

    private void assertNoLoggedText(String... texts) {
        for (var event : appender.list) {
            for (String text : texts) {
                assertFalse(event.getFormattedMessage().contains(text));
            }
        }
    }

    private static ChatResponseMetadata usageMetadata() {
        return ChatResponseMetadata.builder().model("test-model").usage(new DefaultUsage(61, 9, 77)).build();
    }

    private static MessageResponse message(long id, String role, String text) {
        return new MessageResponse(id, 10L, role, text, id, MessageStatus.COMPLETED, null, null, null, null);
    }
}
