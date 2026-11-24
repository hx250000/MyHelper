package com.inshot.hx.myhelper.common.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.inshot.hx.myhelper.common.api.ApiResponse;
import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.dto.request.CreateConversationRequest;
import com.inshot.hx.myhelper.dto.request.RegisterRequest;
import com.inshot.hx.myhelper.dto.request.SaveMessageRequest;
import com.inshot.hx.myhelper.dto.request.SendMessageRequest;
import com.inshot.hx.myhelper.dto.response.MessageResponse;
import com.inshot.hx.myhelper.entity.Conversation;
import com.inshot.hx.myhelper.entity.Message;
import com.inshot.hx.myhelper.entity.User;
import com.inshot.hx.myhelper.entity.enums.MessageRole;
import com.inshot.hx.myhelper.entity.enums.MessageStatus;
import com.inshot.hx.myhelper.repository.ConversationRepository;
import com.inshot.hx.myhelper.repository.MessageRepository;
import com.inshot.hx.myhelper.repository.UserRepository;
import com.inshot.hx.myhelper.service.AiService;
import com.inshot.hx.myhelper.tools.ChatContextBudget;
import com.inshot.hx.myhelper.service.ChatService;
import com.inshot.hx.myhelper.service.ConversationService;
import com.inshot.hx.myhelper.service.MessageService;
import com.inshot.hx.myhelper.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 同时断言原始异常和抛出位置的日志，不依赖 MVC 路由或外部数据库、模型。 */
class ExceptionLoggingTest {

    private final Logger packageLogger = (Logger) LoggerFactory.getLogger("com.inshot.hx.myhelper");
    private ListAppender<ILoggingEvent> appender;
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        previousLevel = packageLogger.getLevel();
        packageLogger.setLevel(Level.TRACE);
        appender = new ListAppender<>();
        appender.start();
        packageLogger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        packageLogger.detachAppender(appender);
        appender.stop();
        packageLogger.setLevel(previousLevel);
    }

    @Test
    void registrationLogsEveryValidationWithoutPasswords() {
        var users = mock(UserRepository.class);
        var service = new UserService(users, mock(PasswordEncoder.class));
        assertLogged(BusinessException.class, () -> service.register(registration(null, "private-password")),
                UserService.class, Level.WARN, "用户名为 null 或空白");
        assertLogged(BusinessException.class, () -> service.register(registration("x".repeat(65), "private-password")),
                UserService.class, Level.WARN, "usernameLength=65");
        assertLogged(BusinessException.class, () -> service.register(registration("alice", null)),
                UserService.class, Level.WARN, "密码为 null 或空白");
        assertLogged(BusinessException.class, () -> service.register(registration("alice", "secret")),
                UserService.class, Level.WARN, "少于 8 个 Unicode 字符");
        assertLogged(BusinessException.class, () -> service.register(registration("alice", "中".repeat(25))),
                UserService.class, Level.WARN, "72 个 UTF-8 字节上限");
        verifyNoInteractions(users);
        when(users.existsByUsername("alice")).thenReturn(true);
        assertLogged(BusinessException.class, () -> service.register(registration("alice", "private-password")),
                UserService.class, Level.WARN, "用户名已被占用");
        assertFalse(logText().contains("private-password"));
        assertFalse(logText().contains("secret"));
        assertFalse(logText().contains("中".repeat(25)));
    }

    @Test
    void registrationDistinguishesConcurrentDuplicateFromOtherDatabaseErrors() {
        var users = mock(UserRepository.class);
        var service = new UserService(users, mock(PasswordEncoder.class));
        var failure = new DataIntegrityViolationException("test integrity violation");
        when(users.saveAndFlush(any(User.class))).thenThrow(failure);
        when(users.existsByUsername("alice")).thenReturn(false, true);
        assertLogged(BusinessException.class, () -> service.register(registration("alice", "private-password")),
                UserService.class, Level.WARN, "重新查询确认用户名已被并发注册", "userId=");
        when(users.existsByUsername("alice")).thenReturn(false);
        assertSame(failure, assertLogged(DataIntegrityViolationException.class,
                () -> service.register(registration("alice", "private-password")),
                UserService.class, Level.ERROR, "非用户名重复的数据完整性异常", "userId="));
        assertNotNull(appender.list.getLast().getThrowableProxy());
    }

    @Test
    void messageValidationLogsReasonsAndNumericContextWithoutContent() {
        var service = new MessageService(mock(MessageRepository.class), mock(ConversationRepository.class));
        assertLogged(BusinessException.class, () -> service.getModelHistory("user-1", 10, 0, 50),
                MessageService.class, Level.WARN, "conversationId=10", "beforeSequence=0");
        assertLogged(BusinessException.class, () -> service.getModelHistory("user-1", 10, 1, 101),
                MessageService.class, Level.WARN, "maxRounds=101");
        assertLogged(BusinessException.class, () -> service.saveUserMessage((SaveMessageRequest) null),
                MessageService.class, Level.WARN, "请求对象为 null");
        assertLogged(BusinessException.class, () -> service.saveUserMessage("user-1", 10L, " "),
                MessageService.class, Level.WARN, "内容为 null 或空白");
        String content = "private-content".repeat(1000);
        assertLogged(BusinessException.class, () -> service.saveUserMessage("user-1", 10L, content),
                MessageService.class, Level.WARN, "conversationId=10", "contentLength=15000");
        assertLogged(BusinessException.class, () -> service.saveAssistantMessage("user-1", 10L, 1L, content, "x".repeat(101), 1),
                MessageService.class, Level.WARN, "modelNameLength=101");
        assertLogged(BusinessException.class, () -> service.saveAssistantMessage("user-1", 10L, 1L, content, "model", -1),
                MessageService.class, Level.WARN, "tokenCount=-1");
        assertLogged(BusinessException.class, () -> service.getHistoryForUser(null, 10L),
                MessageService.class, Level.WARN, "用户 ID 为空", "userIdLength=null");
        assertLogged(BusinessException.class, () -> service.getHistoryForUser("user-1", 0L),
                MessageService.class, Level.WARN, "会话 ID 必须为正整数", "conversationId=0");
        assertFalse(logText().contains("private-content"));
    }

    @Test
    void messageQueriesAndPersistenceLogMissingAccessDeletedAndOverflow() {
        var messages = mock(MessageRepository.class);
        var conversations = mock(ConversationRepository.class);
        var service = new MessageService(messages, conversations);
        assertLogged(BusinessException.class, () -> service.getModelHistory("user-1", 10, 1, 50),
                MessageService.class, Level.WARN, "查询模型历史失败：会话不存在", "conversationId=10");
        assertLogged(BusinessException.class, () -> service.getHistoryForUser("user-1", 10L),
                MessageService.class, Level.WARN, "查询展示历史失败：会话不存在", "userId=user-1");
        assertLogged(BusinessException.class, () -> service.saveUserMessage("user-1", 10L, "private-content"),
                MessageService.class, Level.WARN, "保存消息失败：会话不存在", "role=USER");
        var conversation = mock(Conversation.class);
        when(conversation.getId()).thenReturn(10L);
        when(conversation.getUser()).thenReturn(new User("owner", "alice"));
        when(conversations.findById(10L)).thenReturn(Optional.of(conversation));
        assertLogged(BusinessException.class, () -> service.getHistoryForUser("user-1", 10L),
                MessageService.class, Level.WARN, "当前用户不是会话所有者", "conversationId=10");
        when(conversation.isDeleted()).thenReturn(true);
        assertLogged(BusinessException.class, () -> service.getHistoryForUser("owner", 10L),
                MessageService.class, Level.WARN, "会话已被软删除");
        when(conversation.isDeleted()).thenReturn(false);
        when(conversations.findByIdForUpdate(10L)).thenReturn(Optional.of(conversation));
        when(messages.findMaxSequence(10L)).thenReturn(Long.MAX_VALUE);
        assertLogged(BusinessException.class, () -> service.saveUserMessage("owner", 10L, "private-content"),
                MessageService.class, Level.WARN, "序号已达 Long 上限", "maxSequence=" + Long.MAX_VALUE);
    }

    @Test
    void conversationOperationsLogValidationAndMissingUsers() {
        var service = new ConversationService(mock(ConversationRepository.class), mock(UserRepository.class));
        assertLogged(BusinessException.class, () -> service.createConversation(null),
                ConversationService.class, Level.WARN, "requestPresent=false");
        var request = new CreateConversationRequest();
        request.setUserId("x".repeat(17));
        assertLogged(BusinessException.class, () -> service.createConversation(request),
                ConversationService.class, Level.WARN, "userIdLength=17");
        request.setUserId("user-1");
        assertLogged(BusinessException.class, () -> service.createConversation(request),
                ConversationService.class, Level.WARN, "创建会话失败：用户不存在", "userId=user-1");
        assertLogged(BusinessException.class, () -> service.getMyConversation("user-1"),
                ConversationService.class, Level.WARN, "查询会话列表失败：用户不存在", "userId=user-1");
    }

    @Test
    void chatLogsInvalidIdAndConcurrentSendAndStillReleasesBusyState() {
        var messages = mock(MessageService.class);
        var service = new ChatService(messages, mock(AiService.class));
        assertLogged(BusinessException.class, () -> service.sendMessage(new SendMessageRequest("user-1", 0L, "hi")),
                ChatService.class, Level.WARN, "会话 ID 必须为正整数", "conversationId=0");
        var request = new SendMessageRequest("user-1", 10L, "hi");
        var failure = new BusinessException(ResultCode.NOT_FOUND, "会话不存在");
        when(messages.saveUserMessage("user-1", 10L, "hi")).thenAnswer(invocation -> {
            assertLogged(BusinessException.class, () -> service.sendMessage(request),
                    ChatService.class, Level.WARN, "拒绝并发发送", "conversationId=10");
            throw failure;
        });
        assertSame(failure, assertThrows(BusinessException.class, () -> service.sendMessage(request)));
        assertSame(failure, assertThrows(BusinessException.class, () -> service.sendMessage(request)));
        verify(messages, times(2)).saveUserMessage("user-1", 10L, "hi");
    }

    @Test
    void aiLogsValidationAndUnsupportedHistoryRole() {
        var model = mock(ChatModel.class);
        var service = new AiService(model, new ChatContextBudget(8192));
        assertLogged(BusinessException.class, () -> service.chat(List.of(), null),
                AiService.class, Level.WARN, "消息为 null 或空白");
        assertLogged(BusinessException.class, () -> service.chat(List.of(), "x".repeat(10001)),
                AiService.class, Level.WARN, "messageLength=10001");
        var item = new MessageResponse(3L, 10L, "tool", "private-content", 1L,
                MessageStatus.COMPLETED, null, null, null, null);
        assertLogged(IllegalArgumentException.class, () -> service.chat(List.of(item), "hi"),
                AiService.class, Level.ERROR, "messageId=3", "conversationId=10", "role=tool");
        verifyNoInteractions(model);
        assertFalse(logText().contains("private-content"));
    }

    @Test
    void aiLogsUpstreamStatusWithoutCredentialsOrProviderBody() {
        var model = mock(ChatModel.class);
        var service = new AiService(model, new ChatContextBudget(8192));
        var upstream = new HttpClientErrorException(HttpStatus.UNAUTHORIZED, "api-key=secret");
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenThrow(upstream);
        var exception = assertLogged(BusinessException.class, () -> service.chat(List.of(), "private-content"),
                AiService.class, Level.WARN, "httpStatus=401", "historySize=0", "messageLength=15");
        new GlobalExceptionHandler().handleBusinessException(exception);
        assertFalse(logText().contains("api-key=secret"));
        assertFalse(logText().contains("private-content"));
        assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
    }

    @Test
    void aiDistinguishesAllEmptyResponseShapes() {
        var model = mock(ChatModel.class);
        var service = new AiService(model, new ChatContextBudget(8192));
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(null);
        assertLogged(BusinessException.class, () -> service.chat(List.of(), "hi"),
                AiService.class, Level.WARN, "响应对象为 null");
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(new ChatResponse(List.of()));
        assertLogged(BusinessException.class, () -> service.chat(List.of(), "hi"),
                AiService.class, Level.WARN, "响应缺少生成结果");
        var response = mock(ChatResponse.class);
        var generation = mock(Generation.class);
        when(response.getResult()).thenReturn(generation);
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(response);
        assertLogged(BusinessException.class, () -> service.chat(List.of(), "hi"),
                AiService.class, Level.WARN, "生成结果缺少输出对象");
        var output = mock(AssistantMessage.class);
        when(generation.getOutput()).thenReturn(output);
        assertLogged(BusinessException.class, () -> service.chat(List.of(), "hi"),
                AiService.class, Level.WARN, "输出文本为 null");
        when(output.getText()).thenReturn(" ");
        assertLogged(BusinessException.class, () -> service.chat(List.of(), "hi"),
                AiService.class, Level.WARN, "输出文本为空白");
    }

    @Test
    void entityAndEnvelopeGuardsLogBeforeThrowingAndKeepNullMessages() {
        assertLogged(IllegalArgumentException.class, () -> new User("", "alice"),
                User.class, Level.ERROR, "用户 ID 为空", "idLength=0");
        assertEquals("用户名不能为空", assertLogged(NullPointerException.class, () -> new User("user-1", null),
                User.class, Level.ERROR, "用户名为 null").getMessage());
        assertEquals("所属用户不能为空", assertLogged(NullPointerException.class, () -> new Conversation(null),
                Conversation.class, Level.ERROR, "所属用户为 null").getMessage());
        var conversation = new Conversation(new User("user-1", "alice"));
        assertLogged(NullPointerException.class, () -> new Message(null, MessageRole.USER, 1, "hi"),
                Message.class, Level.ERROR, "所属会话为 null");
        assertLogged(NullPointerException.class, () -> new Message(conversation, null, 1, "hi"),
                Message.class, Level.ERROR, "消息角色为 null");
        assertLogged(IllegalArgumentException.class, () -> new Message(conversation, MessageRole.USER, 0, "hi"),
                Message.class, Level.ERROR, "sequence=0");
        assertLogged(NullPointerException.class, () -> new Message(conversation, MessageRole.USER, 1, null),
                Message.class, Level.ERROR, "消息内容为 null");
        assertLogged(IllegalArgumentException.class, () -> MessageRole.fromValue("unknown"),
                MessageRole.class, Level.ERROR, "value=unknown", "允许值为 user/assistant/system/tool");
        assertLogged(IllegalArgumentException.class, () -> ApiResponse.failure(ResultCode.SUCCESS),
                ApiResponse.class, Level.ERROR, "不能使用成功状态码", "code=0");
        assertLogged(IllegalArgumentException.class, () -> new BusinessException(ResultCode.SUCCESS),
                BusinessException.class, Level.ERROR, "非错误状态码", "status=200");
        assertEquals("message", assertLogged(NullPointerException.class,
                () -> new BusinessException(ResultCode.BAD_REQUEST, null),
                BusinessException.class, Level.ERROR, "异常提示 message 为 null").getMessage());
        assertEquals("resultCode", assertLogged(NullPointerException.class,
                () -> new BusinessException(null, "hi"),
                BusinessException.class, Level.ERROR, "业务状态码 resultCode 为 null").getMessage());
    }

    @Test
    void globalHandlerLogsBusinessAndMvcClientErrorsWithoutRejectedValues() {
        var handler = new GlobalExceptionHandler();
        handler.handleBusinessException(new BusinessException(ResultCode.NOT_FOUND, "会话不存在"));
        assertEvent(GlobalExceptionHandler.class, Level.WARN, "code=40400", "status=404", "reason=会话不存在");
        var exception = new MethodArgumentTypeMismatchException("private-input", Long.class, "id", null,
                new IllegalArgumentException("private-input"));
        handler.handleExceptionInternal(exception, null, HttpHeaders.EMPTY, HttpStatus.BAD_REQUEST,
                new ServletWebRequest(new MockHttpServletRequest()));
        assertEvent(GlobalExceptionHandler.class, Level.WARN, "status=400", "MethodArgumentTypeMismatchException");
        assertFalse(logText().contains("private-input"));
    }

    private <T extends Throwable> T assertLogged(Class<T> type, Executable action, Class<?> source,
                                                 Level level, String... details) {
        int start = appender.list.size();
        T exception = assertThrows(type, action);
        assertEvent(appender.list.subList(start, appender.list.size()), source, level, details);
        return exception;
    }

    private void assertEvent(Class<?> source, Level level, String... details) {
        assertEvent(appender.list, source, level, details);
    }

    private void assertEvent(List<ILoggingEvent> events, Class<?> source, Level level, String... details) {
        assertTrue(events.stream().anyMatch(event -> event.getLoggerName().equals(source.getName())
                        && event.getLevel().equals(level)
                        && Arrays.stream(details).allMatch(event.getFormattedMessage()::contains)),
                () -> "缺少预期的异常日志：" + Arrays.toString(details) + "，实际：" + logText());
    }

    private String logText() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private RegisterRequest registration(String username, String password) {
        var request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }
}
