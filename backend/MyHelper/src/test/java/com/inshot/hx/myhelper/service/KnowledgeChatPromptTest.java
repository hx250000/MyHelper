package com.inshot.hx.myhelper.service;

import com.inshot.hx.myhelper.controller.ChatController;
import com.inshot.hx.myhelper.dto.request.SendMessageRequest;
import com.inshot.hx.myhelper.dto.response.KnowledgeReference;
import com.inshot.hx.myhelper.dto.response.MessageResponse;
import com.inshot.hx.myhelper.entity.enums.MessageStatus;
import com.inshot.hx.myhelper.tools.ChatContextBudget;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Compares the outgoing model prompt with the send API's references without external model calls. */
class KnowledgeChatPromptTest {

    private static final String CIDR_QUESTION = "IPv4 CIDR /26 子网的广播地址和可用主机地址范围是什么？";
    private static final String NET_04_TEXT = """
            ## NET-04｜IPv4 子网与 CIDR
            示例子网 192.0.2.64/26 的广播地址是 192.0.2.127，\
            可分配主机地址为 192.0.2.65 至 192.0.2.126。
            """.stripTrailing();
    private static final List<MessageResponse> HISTORY = List.of(
            message(1L, "user", "前一轮问题"), message(2L, "assistant", "前一轮回答"));
    private static final KnowledgeReference NET_04_REFERENCE =
            new KnowledgeReference("computer-network-kb.md", "NET-04｜IPv4 子网与 CIDR");

    @Test
    void includesTheMatchedSourceAndTextButNotInternalVectorMetadata() throws Exception {
        var prompt = send(CIDR_QUESTION, List.of(net04()), HISTORY, new ChatContextBudget(8192), NET_04_REFERENCE);

        assertEquals(3, prompt.getInstructions().size());
        var latest = assertInstanceOf(UserMessage.class, prompt.getInstructions().getLast());
        assertTrue(latest.getText().contains("当前问题：\n" + CIDR_QUESTION));
        assertTrue(latest.getText().contains("参考资料：\n来源：computer-network-kb.md\n" + NET_04_TEXT));
        for (String key : List.of("chunk_index", "total_chunks", "parent_document_id", "charset", "distance")) {
            assertFalse(latest.getText().contains(key), key);
        }
    }

    @Test
    void preservesTheOriginalQuestionAndHistoryWhenSearchReturnsNothing() throws Exception {
        String question = "NET-04 的示例子网，广播地址和可用主机地址范围分别是什么？";

        var prompt = send(question, List.of(), HISTORY, new ChatContextBudget(8192));

        assertInstanceOf(UserMessage.class, prompt.getInstructions().getLast());
        assertEquals(List.of("前一轮问题", "前一轮回答", question),
                prompt.getInstructions().stream().map(Message::getText).toList());
    }

    @Test
    void skipsAnOversizedHitAndUsesTheRemainingBudgetForCompleteTurns() throws Exception {
        int limit = 512;
        var budget = new ChatContextBudget(limit);
        var oversized = new Document(" oversized".repeat(1500), Map.of("source", "oversized.md"));
        var history = List.of(message(1L, "user", " older".repeat(420)),
                message(2L, "assistant", "较早回复"), message(3L, "user", "最近问题"),
                message(4L, "assistant", "最近回复"));

        var prompt = send(CIDR_QUESTION, List.of(oversized, net04()), history, budget, NET_04_REFERENCE);

        var texts = prompt.getInstructions().stream().map(Message::getText).toList();
        assertEquals(3, texts.size());
        assertEquals(List.of("最近问题", "最近回复"), texts.subList(0, 2));
        assertTrue(texts.getLast().contains(NET_04_TEXT));
        assertFalse(texts.getLast().contains("oversized.md"));
        assertTrue(texts.stream().mapToInt(budget::estimateMessage).sum() <= limit);
    }

    @Test
    void keepsReferenceOrderAndAllowsChunksWithoutSectionHeadings() throws Exception {
        var intro = new Document("入门概要", Map.of("source", "guide.md"));
        var sourceUnknown = new Document("没有来源信息的片段");
        var prompt = send(CIDR_QUESTION, List.of(intro, sourceUnknown, net04()), List.of(),
                new ChatContextBudget(8192), new KnowledgeReference("guide.md", null), NET_04_REFERENCE);

        var text = prompt.getInstructions().getLast().getText();
        assertTrue(text.contains("来源：guide.md\n入门概要\n---\n没有来源信息的片段\n---\n来源：computer-network-kb.md"));
    }

    private static Prompt send(String question, List<Document> results, List<MessageResponse> history,
                               ChatContextBudget budget, KnowledgeReference... expectedReferences) throws Exception {
        var messages = mock(MessageService.class);
        var vectorStore = mock(VectorStore.class);
        var model = mock(ChatModel.class);
        var knowledge = new KnowledgeService(vectorStore);
        var service = new ChatService(messages, new AiService(model, budget), Optional.of(knowledge));
        when(messages.saveUserMessage("user-1", 10L, question)).thenReturn(message(41L, "user", question));
        when(messages.getModelHistory("user-1", 10L, 41L, 15L)).thenReturn(history);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(results);
        when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("测试回复")))));
        when(messages.saveAssistantMessage("user-1", 10L, 41L, "测试回复", null, 0))
                .thenReturn(message(42L, "assistant", "测试回复"));

        var mvc = MockMvcBuilders.standaloneSetup(new ChatController(service)).build();
        var response = mvc.perform(post("/api/chat/send").contentType(MediaType.APPLICATION_JSON)
                        .content(JsonMapper.builder().build()
                                .writeValueAsString(new SendMessageRequest("user-1", 10L, question))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.references.length()").value(expectedReferences.length));
        for (int i = 0; i < expectedReferences.length; i++) {
            var expected = expectedReferences[i];
            var referencePath = "$.data.references[" + i + "]";
            response.andExpect(jsonPath(referencePath + ".source").value(expected.source()))
                    .andExpect(jsonPath(referencePath + ".text").doesNotExist())
                    .andExpect(jsonPath(referencePath + ".metadata").doesNotExist());
            if (expected.sectionTitle() == null) {
                response.andExpect(jsonPath(referencePath + ".sectionTitle").value(nullValue()));
            } else {
                response.andExpect(jsonPath(referencePath + ".sectionTitle").value(expected.sectionTitle()));
            }
        }

        verify(vectorStore).similaritySearch(argThat((SearchRequest request) -> question.equals(request.getQuery())));
        verify(messages).saveUserMessage("user-1", 10L, question);
        var captor = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(captor.capture());
        return captor.getValue();
    }

    private static Document net04() {
        return new Document(NET_04_TEXT, Map.of("source", "computer-network-kb.md",
                "chunk_index", 3, "total_chunks", 15, "charset", "UTF-8",
                "parent_document_id", "internal-document-id", "distance", 0.25));
    }

    private static MessageResponse message(long id, String role, String text) {
        return new MessageResponse(id, 10L, role, text, id, MessageStatus.COMPLETED, null, null, null, null);
    }
}
