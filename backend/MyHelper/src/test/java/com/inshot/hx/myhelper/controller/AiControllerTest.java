package com.inshot.hx.myhelper.controller;

import com.inshot.hx.myhelper.common.exception.GlobalExceptionHandler;
import com.inshot.hx.myhelper.service.AiService;
import com.inshot.hx.myhelper.tools.ChatContextBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiControllerTest {

    private MockMvc mvc;
    private final AtomicReference<Prompt> prompt = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        useModel(request -> {
            prompt.set(request);
            calls.incrementAndGet();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("你好！"))));
        });
    }

    private void useModel(ChatModel model) {
        mvc = MockMvcBuilders.standaloneSetup(new AiController(new AiService(model, new ChatContextBudget(8192))))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void sendsMessageAndReturnsTextInUnifiedEnvelope() throws Exception {
        mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"  你好 {name}  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.reply").value("你好！"))
                .andExpect(jsonPath("$.data.id").value(nullValue()))
                .andExpect(jsonPath("$.data.model").value(nullValue()))
                .andExpect(jsonPath("$.data.usage").value(nullValue()));
        assertEquals("你好 {name}", prompt.get().getContents());
        assertEquals(1, prompt.get().getInstructions().size());
    }

    @Test
    void keepsMetadataAndProviderTotalForTruncatedResponse() throws Exception {
        useModel(request -> new ChatResponse(List.of(new Generation(new AssistantMessage("未完成的回复"),
                ChatGenerationMetadata.builder().finishReason("length").build())),
                ChatResponseMetadata.builder().id("chat-123").model("gemini-test")
                        .usage(new DefaultUsage(3, 2, 8)).build()));

        mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reply").value("未完成的回复"))
                .andExpect(jsonPath("$.data.id").value("chat-123"))
                .andExpect(jsonPath("$.data.model").value("gemini-test"))
                .andExpect(jsonPath("$.data.finishReason").value("length"))
                .andExpect(jsonPath("$.data.usage.promptTokens").value(3))
                .andExpect(jsonPath("$.data.usage.completionTokens").value(2))
                .andExpect(jsonPath("$.data.usage.totalTokens").value(8));
    }

    @Test
    void enforcesJsonContentTypeAndResponseFormat() throws Exception {
        mvc.perform(post("/api/ai/chat").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(41500));
        mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_HTML).content("{\"message\":\"你好\"}"))
                // 客户端明确拒收 JSON 时，只断言 HTTP 状态，不要求 JSON 错误体。
                .andExpect(status().isNotAcceptable());
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsMissingBlankAndOversizedMessagesWithoutCallingModel() throws Exception {
        for (String body : List.of("{}", "{\"message\":null}", "{\"message\":\"   \"}",
                "{\"message\":\"" + "x".repeat(10001) + "\"}")) {
            mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsMalformedAndMissingBodies() throws Exception {
        for (String body : List.of("{", "", "null")) {
            mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        assertEquals(0, calls.get());
    }

    @Test
    void upstreamFailureDoesNotExposeProviderErrorOrCredentials() throws Exception {
        useModel(request -> { throw new IllegalStateException("api-key=secret upstream error"); });
        mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(50200))
                .andExpect(jsonPath("$.message").value("大模型请求失败，请检查后端的密钥、模型名称及网络连接后重试"));
    }

    @Test
    void handlesEmptyAndMissingModelResults() throws Exception {
        for (ChatResponse response : List.of(new ChatResponse(List.of()),
                new ChatResponse(List.of(new Generation(new AssistantMessage(" ")))))) {
            useModel(request -> response);
            expectEmptyReply();
        }
        useModel(request -> null);
        expectEmptyReply();
    }

    private void expectEmptyReply() throws Exception {
        mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(50200))
                .andExpect(jsonPath("$.message").value("大模型未返回文本，请重试"));
    }
}
