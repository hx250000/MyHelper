package com.inshot.hx.myhelper.controller;

import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.common.exception.BusinessException;
import com.inshot.hx.myhelper.common.exception.GlobalExceptionHandler;
import com.inshot.hx.myhelper.dto.request.SaveMessageRequest;
import com.inshot.hx.myhelper.entity.Conversation;
import com.inshot.hx.myhelper.entity.User;
import com.inshot.hx.myhelper.entity.enums.MessageRole;
import com.inshot.hx.myhelper.entity.enums.MessageStatus;
import com.inshot.hx.myhelper.repository.ConversationRepository;
import com.inshot.hx.myhelper.repository.MessageRepository;
import com.inshot.hx.myhelper.repository.UserRepository;
import com.inshot.hx.myhelper.service.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 实际提交事务，使用 H2 验证消息落库、关联隔离与并发序号；不调用真实 AI/MySQL。 */
@DataJpaTest(properties = {
        "spring.autoconfigure.exclude=",
        "spring.datasource.url=jdbc:h2:mem:message-persistence;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MessageService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MessagePersistenceTest {

    @Autowired
    private MessageService messageService;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private MockMvc mvc;
    private Long conversationId;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAllInBatch();
        conversationRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        User user = new User("user-1", "alice");
        user.setPasswordHash("private-password-hash");
        userRepository.saveAndFlush(user);
        conversationId = conversationRepository.saveAndFlush(new Conversation(user)).getId();
        mvc = MockMvcBuilders.standaloneSetup(new MessageController(messageService))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void savesUserMessageWithoutReplyAssociation() {
        var response = messageService.saveUserMessage(" user-1 ", conversationId, "  你好\n世界  ");
        assertEquals("user", response.role());
        assertEquals(1L, response.sequence());
        assertEquals(MessageStatus.COMPLETED, response.status());
        assertNull(response.modelName());
        assertNull(response.tokenCount());

        assertEquals(1, messageRepository.count());
        var saved = messageRepository.findAll().getFirst();
        assertEquals(MessageRole.USER, saved.getRole());
        assertEquals(MessageStatus.COMPLETED, saved.getStatus());
        assertEquals("  你好\n世界  ", saved.getContent());
        assertEquals(conversationId, saved.getConversation().getId());
        assertNull(saved.getReplyTo());
    }

    @Test
    void directMessageWriteEndpointsRemainUnavailable() throws Exception {
        for (String path : List.of("/api/messages/user", "/api/messages/assistant")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"user-1\",\"conversationId\":%d,\"content\":\"消息\"}".formatted(conversationId)))
                    .andExpect(status().isNotFound());
        }
        assertEquals(0, messageRepository.count());
    }

    @Test
    void savesAssistantReplyInternallyAndAllocatesSequencesAcrossRoles() {
        var userMessage = messageService.saveUserMessage("user-1", conversationId, "你好");
        var reply = messageService.saveAssistantMessage("user-1", conversationId, userMessage.id(), "你好！", "test-model", 8);
        var nextMessage = messageService.saveUserMessage("user-1", conversationId, "继续");

        assertEquals(1L, userMessage.sequence());
        assertEquals(2L, reply.sequence());
        assertEquals(3L, nextMessage.sequence());
        assertEquals("assistant", reply.role());
        assertEquals(MessageStatus.COMPLETED, reply.status());
        assertEquals("test-model", reply.modelName());
        assertEquals(8, reply.tokenCount());
        assertNotNull(reply.createdAt());
        assertEquals(3, messageRepository.count());
        var saved = messageRepository.findById(reply.id()).orElseThrow();
        assertEquals(MessageRole.ASSISTANT, saved.getRole());
        assertEquals(MessageStatus.COMPLETED, saved.getStatus());
        assertEquals("你好！", saved.getContent());
        assertEquals("test-model", saved.getModelName());
        assertEquals(8, saved.getTokenCount());
        assertEquals(userMessage.id(), jdbcTemplate.queryForObject(
                "select reply_to from message where id = ?", Long.class, reply.id()));
    }

    @Test
    void acceptsUserMessageAtLengthLimitAndLongerAssistantReply() {
        var userMessage = messageService.saveUserMessage("user-1", conversationId, "x".repeat(10000));
        var reply = messageService.saveAssistantMessage("user-1", conversationId, userMessage.id(), "答".repeat(12000), null, null);
        assertEquals(10000, userMessage.content().length());
        assertNull(reply.modelName());
        assertNull(reply.tokenCount());
        assertEquals(12000, messageRepository.findById(reply.id()).orElseThrow().getContent().length());
    }

    @Test
    void rejectsUnlinkedCrossConversationAndNonUserReplyTargets() {
        var owner = userRepository.findById("user-1").orElseThrow();
        var other = conversationRepository.saveAndFlush(new Conversation(owner));
        var otherQuestion = messageService.saveUserMessage("user-1", other.getId(), "另一会话的问题");
        var question = messageService.saveUserMessage("user-1", conversationId, "问题");
        var reply = messageService.saveAssistantMessage("user-1", conversationId, question.id(), "回复", null, null);

        for (Long target : new Long[]{null, 0L, Long.MAX_VALUE, otherQuestion.id(), reply.id()}) {
            assertError(ResultCode.BAD_REQUEST,
                    () -> messageService.saveAssistantMessage("user-1", conversationId, target, "错误回复", null, null));
        }
        assertError(ResultCode.CONFLICT,
                () -> messageService.saveAssistantMessage("user-1", conversationId, question.id(), "重复回复", null, null));
        assertEquals(3, messageRepository.count());
    }

    @Test
    void databaseRejectsMissingAndDuplicateReplyTargets() {
        var question = messageService.saveUserMessage("user-1", conversationId, "问题");
        messageService.saveAssistantMessage("user-1", conversationId, question.id(), "回复", null, null);
        var other = messageService.saveUserMessage("user-1", conversationId, "另一问题");

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "update message set reply_to = ? where id = ?", Long.MAX_VALUE, other.id()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "update message set reply_to = ? where id = ?", question.id(), other.id()));
    }

    @Test
    void updatesConversationActivityTimeInSameTransaction() {
        LocalDateTime oldTime = LocalDateTime.of(2020, 1, 1, 0, 0);
        jdbcTemplate.update("update conversation set updated_at = ? where id = ?", oldTime, conversationId);

        messageService.saveUserMessage(request("user-1", conversationId, "新消息"));

        assertTrue(conversationRepository.findById(conversationId).orElseThrow().getUpdatedAt().isAfter(oldTime));
    }

    @Test
    void rollsBackMessageAndConversationActivityTogether() {
        LocalDateTime oldTime = LocalDateTime.of(2020, 1, 1, 0, 0);
        jdbcTemplate.update("update conversation set updated_at = ? where id = ?", oldTime, conversationId);

        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(transaction -> {
                    messageService.saveUserMessage(request("user-1", conversationId, "应回滚的消息"));
                    throw new IllegalStateException("模拟后续事务步骤失败");
                }));

        assertEquals(0, messageRepository.count());
        assertEquals(oldTime, conversationRepository.findById(conversationId).orElseThrow().getUpdatedAt());
        assertEquals(1L, messageService.saveUserMessage(request("user-1", conversationId, "重新保存")).sequence());
    }

    @Test
    void startsSequenceAtOneForEachConversation() {
        var user = userRepository.findById("user-1").orElseThrow();
        Long otherId = conversationRepository.saveAndFlush(new Conversation(user)).getId();
        assertEquals(1L, messageService.saveUserMessage(request("user-1", conversationId, "第一条")).sequence());
        assertEquals(1L, messageService.saveUserMessage(request("user-1", otherId, "另一会话")).sequence());
    }

    @Test
    void rejectsOtherUsersConversation() {
        userRepository.saveAndFlush(new User("user-2", "bob"));
        assertError(ResultCode.FORBIDDEN,
                () -> messageService.saveUserMessage("user-2", conversationId, "不能写入"));
        assertError(ResultCode.FORBIDDEN,
                () -> messageService.saveAssistantMessage("user-2", conversationId, 1L, "也不能写入", null, null));
        assertEquals(0, messageRepository.count());
    }

    @Test
    void rejectsDeletedAndMissingConversations() {
        var conversation = conversationRepository.findById(conversationId).orElseThrow();
        conversation.setDeleted(true);
        conversationRepository.saveAndFlush(conversation);

        for (Long id : List.of(conversationId, Long.MAX_VALUE)) {
            assertError(ResultCode.NOT_FOUND,
                    () -> messageService.saveUserMessage("user-1", id, "不能写入"));
            assertError(ResultCode.NOT_FOUND,
                    () -> messageService.saveAssistantMessage("user-1", id, 1L, "也不能写入", null, null));
        }
        assertEquals(0, messageRepository.count());
    }

    @Test
    void rejectsInvalidRequestsWithoutSaving() {
        for (String userId : new String[]{null, "", " ", "u".repeat(17)}) {
            assertError(ResultCode.BAD_REQUEST,
                    () -> messageService.saveUserMessage(userId, conversationId, "消息"));
        }
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertError(ResultCode.BAD_REQUEST, () -> messageService.saveUserMessage("user-1", id, "消息"));
        }
        assertThrows(BusinessException.class, () -> messageService.saveUserMessage(null));
        assertEquals(0, messageRepository.count());
    }

    @Test
    void rejectsInvalidUserContentWhenSavingWithParameters() {
        for (String content : new String[]{null, "", "  ", "x".repeat(10001)}) {
            assertThrows(BusinessException.class,
                    () -> messageService.saveUserMessage("user-1", conversationId, content));
        }
        assertEquals(0, messageRepository.count());
    }

    @Test
    void rejectsInvalidAssistantContentAndMetadata() {
        assertThrows(BusinessException.class,
                () -> messageService.saveAssistantMessage("user-1", conversationId, 1L, null, null, null));
        assertThrows(BusinessException.class,
                () -> messageService.saveAssistantMessage("user-1", conversationId, 1L, " ", null, null));
        assertThrows(BusinessException.class,
                () -> messageService.saveAssistantMessage("user-1", conversationId, 1L, "回复", "m".repeat(101), null));
        assertThrows(BusinessException.class,
                () -> messageService.saveAssistantMessage("user-1", conversationId, 1L, "回复", null, -1));
        assertEquals(0, messageRepository.count());
    }

    @Test
    void serializesConcurrentWritesWithoutDuplicateSequence() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("未收到并发测试启动信号");
                    }
                    return messageService.saveUserMessage(request("user-1", conversationId, "消息" + index)).sequence();
                }));
            }
            start.countDown();
            List<Long> sequences = new ArrayList<>();
            for (Future<Long> future : futures) {
                sequences.add(future.get(15, TimeUnit.SECONDS));
            }
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), sequences.stream().sorted().toList());
            assertEquals(8, messageRepository.count());
        }
    }

    private SaveMessageRequest request(String userId, Long id, String content) {
        SaveMessageRequest request = new SaveMessageRequest();
        request.setUserId(userId);
        request.setConversationId(id);
        request.setContent(content);
        return request;
    }

    private void assertError(ResultCode code, Runnable action) {
        assertEquals(code, assertThrows(BusinessException.class, action::run).getResultCode());
    }
}
