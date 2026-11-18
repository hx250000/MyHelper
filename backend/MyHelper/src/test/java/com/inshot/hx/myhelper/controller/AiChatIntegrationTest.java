package com.inshot.hx.myhelper.controller;

import com.inshot.hx.myhelper.common.exception.GlobalExceptionHandler;
import com.inshot.hx.myhelper.service.AiService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用真正的 Spring AI 客户端和本地 HTTP 上游，不访问 Gemini、不需要真实密钥。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AiChatIntegrationTest {

    private static final AtomicReference<String> path = new AtomicReference<>();
    private static final AtomicReference<String> authorization = new AtomicReference<>();
    private static final AtomicReference<String> requestBody = new AtomicReference<>();
    private static final AtomicInteger upstreamStatus = new AtomicInteger(200);
    private static final AtomicInteger calls = new AtomicInteger();
    private static final HttpServer upstream = startUpstream();

    @Autowired
    private AiService aiService;
    private MockMvc mvc;

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                calls.incrementAndGet();
                path.set(exchange.getRequestURI().getPath());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String response = upstreamStatus.get() == 200 ? """
                        {"id":"test-chat","object":"chat.completion","created":1,"model":"test-model",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"连接成功"},
                         "finish_reason":"stop"}],
                         "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}
                        """ : """
                        {"error":{"message":"upstream-private-details","type":"permission_denied","code":"403"}}
                        """;
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(upstreamStatus.get(), bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("无法启动本地测试上游", exception);
        }
    }

    @DynamicPropertySource
    static void modelProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.api-key", () -> "test-key");
        registry.add("spring.ai.openai.base-url",
                () -> "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1beta/openai/");
        registry.add("spring.ai.openai.chat.model", () -> "test-model");
    }

    @BeforeEach
    void setUp() {
        upstreamStatus.set(200);
        calls.set(0);
        mvc = MockMvcBuilders.standaloneSetup(new AiController(aiService))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop(0);
    }

    @Test
    void sendsCompatibleRequestAndParsesRealClientResponse() throws Exception {
        mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.reply").value("连接成功"))
                .andExpect(jsonPath("$.data.id").value("test-chat"))
                .andExpect(jsonPath("$.data.model").value("test-model"))
                .andExpect(jsonPath("$.data.finishReason").value("stop"))
                .andExpect(jsonPath("$.data.usage.promptTokens").value(3))
                .andExpect(jsonPath("$.data.usage.completionTokens").value(2))
                .andExpect(jsonPath("$.data.usage.totalTokens").value(5));

        assertEquals("/v1beta/openai/chat/completions", path.get());
        assertEquals("Bearer test-key", authorization.get());
        Map<?, ?> body = JsonMapper.builder().build().readValue(requestBody.get(), Map.class);
        assertEquals("test-model", body.get("model"));
        List<?> messages = (List<?>) body.get("messages");
        assertEquals(1, messages.size());
        assertEquals("user", ((Map<?, ?>) messages.getFirst()).get("role"));
        assertEquals("你好", ((Map<?, ?>) messages.getFirst()).get("content"));
        assertEquals(1, calls.get());
    }

    @Test
    void convertsProviderFailureToPublicErrorWithoutRetry() throws Exception {
        upstreamStatus.set(403);
        mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(50200))
                .andExpect(jsonPath("$.message").value("大模型请求失败，请检查后端的密钥、模型名称及网络连接后重试"));
        assertEquals(1, calls.get());
    }
}
