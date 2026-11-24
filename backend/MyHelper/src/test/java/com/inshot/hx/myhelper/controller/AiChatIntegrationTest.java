package com.inshot.hx.myhelper.controller;

import com.inshot.hx.myhelper.common.exception.GlobalExceptionHandler;
import com.inshot.hx.myhelper.entity.Conversation;
import com.inshot.hx.myhelper.entity.User;
import com.inshot.hx.myhelper.repository.ConversationRepository;
import com.inshot.hx.myhelper.repository.MessageRepository;
import com.inshot.hx.myhelper.repository.UserRepository;
import com.inshot.hx.myhelper.service.AiService;
import com.inshot.hx.myhelper.service.ChatService;
import com.inshot.hx.myhelper.service.MessageService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用真正的 Spring AI 客户端和本地 HTTP 上游，不访问 Gemini、不需要真实密钥。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.config.import=",
        "spring.datasource.url=jdbc:h2:mem:ai-chat;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.ai.model.embedding=none"
})
class AiChatIntegrationTest {

    private static final AtomicReference<String> path = new AtomicReference<>();
    private static final AtomicReference<String> authorization = new AtomicReference<>();
    private static final AtomicReference<String> requestBody = new AtomicReference<>();
    private static final AtomicInteger upstreamStatus = new AtomicInteger(200);
    private static final AtomicInteger calls = new AtomicInteger();
    private static final HttpServer upstream = startUpstream();

    @Autowired
    private AiService aiService;
    @Autowired
    private ChatService chatService;
    @Autowired
    private MessageService messageService;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private UserRepository userRepository;
    private Long conversationId;
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
        messageRepository.deleteAllInBatch();
        conversationRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        User user = userRepository.saveAndFlush(new User("user-1", "alice"));
        conversationId = conversationRepository.saveAndFlush(new Conversation(user)).getId();
        mvc = MockMvcBuilders.standaloneSetup(new AiController(aiService),
                        new ChatController(chatService), new MessageController(messageService))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop(0);
    }

    @Test
    void sendsCompatibleRequestAndParsesRealClientResponse() throws Exception {
        mvc.perform(post("/api/ai/chat/nomemory").contentType(MediaType.APPLICATION_JSON)
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
        mvc.perform(post("/api/ai/chat/nomemory").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(50200))
                .andExpect(jsonPath("$.message").value("大模型暂不可用"));
        assertEquals(1, calls.get());
    }

    @Test
    void sendsAndPersistsOneRoundAndReturnsItInHistory() throws Exception {
        mvc.perform(post("/api/chat/send").contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("user-1", conversationId, "  你好  ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userMessage.id").isNumber())
                .andExpect(jsonPath("$.data.userMessage.conversationId").value(conversationId))
                .andExpect(jsonPath("$.data.userMessage.role").value("user"))
                .andExpect(jsonPath("$.data.userMessage.content").value("  你好  "))
                .andExpect(jsonPath("$.data.userMessage.sequence").value(1))
                .andExpect(jsonPath("$.data.userMessage.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.assistantMessage.id").isNumber())
                .andExpect(jsonPath("$.data.assistantMessage.role").value("assistant"))
                .andExpect(jsonPath("$.data.assistantMessage.content").value("连接成功"))
                .andExpect(jsonPath("$.data.assistantMessage.sequence").value(2))
                .andExpect(jsonPath("$.data.assistantMessage.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.assistantMessage.modelName").value("test-model"))
                .andExpect(jsonPath("$.data.assistantMessage.tokenCount").value(2));
        assertEquals(2, messageRepository.count());
        var saved = messageService.getHistoryForUser("user-1", conversationId);
        assertEquals(saved.getFirst().id(), jdbcTemplate.queryForObject(
                "select reply_to from message where id = ?", Long.class, saved.getLast().id()));
        mvc.perform(get("/api/messages/history").param("userId", "user-1")
                        .param("conversationId", conversationId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].content").value("  你好  "))
                .andExpect(jsonPath("$.data[1].content").value("连接成功"));

        mvc.perform(post("/api/chat/send").contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("user-1", conversationId, "新问题")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userMessage.sequence").value(3))
                .andExpect(jsonPath("$.data.assistantMessage.sequence").value(4));
        Map<?, ?> body = JsonMapper.builder().build().readValue(requestBody.get(), Map.class);
        assertEquals(List.of(
                Map.of("role", "user", "content", "  你好  "),
                Map.of("role", "assistant", "content", "连接成功"),
                Map.of("role", "user", "content", "新问题")), body.get("messages"));
        assertEquals(2, calls.get());
    }

    @Test
    void modelFailureKeepsUserMessageButDoesNotSaveFakeReply() throws Exception {
        upstreamStatus.set(403);
        mvc.perform(post("/api/chat/send").contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("user-1", conversationId, "你好")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(50200))
                .andExpect(jsonPath("$.data").value(nullValue()));
        assertEquals(1, calls.get());
        var history = messageService.getHistoryForUser("user-1", conversationId);
        assertEquals(1, history.size());
        assertEquals("user", history.getFirst().role());
    }

    @Test
    void rejectsInvalidOrInaccessibleRequestsBeforeCallingModel() throws Exception {
        for (String body : List.of("{}", "null", "{", "",
                sendBody("user-1", conversationId, " "),
                sendBody("user-1", conversationId, "x".repeat(10001)),
                sendBody("", conversationId, "你好"),
                sendBody("u".repeat(17), conversationId, "你好"),
                sendBody("user-1", 0L, "你好"))) {
            mvc.perform(post("/api/chat/send").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/chat/send").contentType(MediaType.TEXT_PLAIN).content("你好"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/chat/send").contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("other-user", conversationId, "你好")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/chat/send").contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("user-1", Long.MAX_VALUE, "你好")))
                .andExpect(status().isNotFound());
        var conversation = conversationRepository.findById(conversationId).orElseThrow();
        conversation.setDeleted(true);
        conversationRepository.saveAndFlush(conversation);
        mvc.perform(post("/api/chat/send").contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody("user-1", conversationId, "你好")))
                .andExpect(status().isNotFound());
        assertEquals(0, calls.get());
        assertEquals(0, messageRepository.count());
    }

    private String sendBody(String userId, Long id, String content) {
        return JsonMapper.builder().build().writeValueAsString(
                Map.of("userId", userId, "conversationId", id, "content", content));
    }
}
