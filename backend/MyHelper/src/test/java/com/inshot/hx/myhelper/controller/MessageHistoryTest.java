package com.inshot.hx.myhelper.controller;

import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.common.exception.BusinessException;
import com.inshot.hx.myhelper.common.exception.GlobalExceptionHandler;
import com.inshot.hx.myhelper.entity.Conversation;
import com.inshot.hx.myhelper.entity.Message;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 实际经过 JPA 和只读 Service 事务，验证历史顺序、会话隔离及 DTO 序列化。 */
@DataJpaTest(properties = {
        "spring.autoconfigure.exclude=",
        "spring.datasource.url=jdbc:h2:mem:message-history;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MessageService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MessageHistoryTest {

    @Autowired
    private MessageService messageService;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private UserRepository userRepository;

    private MockMvc mvc;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAllInBatch();
        conversationRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        User user = new User("user-1", "alice");
        user.setPasswordHash("private-password-hash");
        userRepository.saveAndFlush(user);
        conversation = conversationRepository.saveAndFlush(new Conversation(user));
        mvc = MockMvcBuilders.standaloneSetup(new MessageController(messageService))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void returnsOnlyCurrentConversationInSequenceOrderWithoutExposingEntities() throws Exception {
        // 故意逆序插入，确保不是依赖主键或创建时间排序。
        save(conversation, MessageRole.USER, 3, "继续", MessageStatus.COMPLETED);
        Message reply = save(conversation, MessageRole.ASSISTANT, 2, "你好！", MessageStatus.COMPLETED);
        reply.setModelName("test-model");
        reply.setTokenCount(8);
        messageRepository.saveAndFlush(reply);
        save(conversation, MessageRole.USER, 1, "  你好\n世界  ", MessageStatus.COMPLETED);
        Conversation other = conversationRepository.saveAndFlush(
                new Conversation(userRepository.findById("user-1").orElseThrow()));
        save(other, MessageRole.USER, 1, "其他会话的消息", MessageStatus.COMPLETED);

        mvc.perform(get("/api/messages/history").param("userId", " user-1 ")
                        .param("conversationId", conversation.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[*].sequence", contains(1, 2, 3)))
                .andExpect(jsonPath("$.data[*].role", contains("user", "assistant", "user")))
                .andExpect(jsonPath("$.data[0].content").value("  你好\n世界  "))
                .andExpect(jsonPath("$.data[0].conversationId").value(conversation.getId().intValue()))
                .andExpect(jsonPath("$.data[1].id").value(reply.getId().intValue()))
                .andExpect(jsonPath("$.data[1].content").value("你好！"))
                .andExpect(jsonPath("$.data[1].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data[1].modelName").value("test-model"))
                .andExpect(jsonPath("$.data[1].tokenCount").value(8))
                .andExpect(jsonPath("$.data[1].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data[1].updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.data[*].conversation").doesNotExist())
                .andExpect(jsonPath("$.data[*].user").doesNotExist())
                .andExpect(content().string(not(containsString("passwordHash"))))
                .andExpect(content().string(not(containsString("private-password-hash"))))
                .andExpect(content().string(not(containsString("其他会话的消息"))));
    }

    @Test
    void returnsEmptyArrayForExistingConversationWithoutMessages() throws Exception {
        mvc.perform(get("/api/messages/history").param("userId", "user-1")
                        .param("conversationId", conversation.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void preservesAllMessageStatusesForDisplay() throws Exception {
        int sequence = 1;
        for (MessageStatus messageStatus : MessageStatus.values()) {
            save(conversation, MessageRole.ASSISTANT, sequence++, "", messageStatus);
        }
        mvc.perform(get("/api/messages/history").param("userId", "user-1")
                        .param("conversationId", conversation.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(MessageStatus.values().length)))
                .andExpect(jsonPath("$.data[*].status",
                        contains("PENDING", "STREAMING", "COMPLETED", "FAILED", "CANCELLED")));
    }

    @Test
    void rejectsAccessToAnotherUsersConversation() throws Exception {
        save(conversation, MessageRole.USER, 1, "私密消息", MessageStatus.COMPLETED);
        userRepository.saveAndFlush(new User("user-2", "bob"));
        mvc.perform(get("/api/messages/history").param("userId", "user-2")
                        .param("conversationId", conversation.getId().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(content().string(not(containsString("私密消息"))));
    }

    @Test
    void rejectsDeletedConversationEvenWhenMessagesExist() throws Exception {
        save(conversation, MessageRole.USER, 1, "已删除会话的消息", MessageStatus.COMPLETED);
        conversation.setDeleted(true);
        conversationRepository.saveAndFlush(conversation);
        mvc.perform(get("/api/messages/history").param("userId", "user-1")
                        .param("conversationId", conversation.getId().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.data").value(nullValue()));
        assertEquals(1, messageRepository.count());
    }

    @Test
    void returnsNotFoundForMissingConversation() throws Exception {
        mvc.perform(get("/api/messages/history").param("userId", "user-1")
                        .param("conversationId", Long.toString(Long.MAX_VALUE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void rejectsMissingAndInvalidParameters() throws Exception {
        for (String userId : new String[]{"", "  ", "u".repeat(17)}) {
            mvc.perform(get("/api/messages/history").param("userId", userId)
                            .param("conversationId", conversation.getId().toString()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        for (String id : new String[]{"", "0", "-1", "abc", "9223372036854775808"}) {
            mvc.perform(get("/api/messages/history").param("userId", "user-1").param("conversationId", id))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }
        mvc.perform(get("/api/messages/history").param("conversationId", conversation.getId().toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/messages/history").param("userId", "user-1"))
                .andExpect(status().isBadRequest());
        BusinessException invalidUser = assertThrows(BusinessException.class,
                () -> messageService.getHistoryForUser(null, conversation.getId()));
        BusinessException invalidConversation = assertThrows(BusinessException.class,
                () -> messageService.getHistoryForUser("user-1", null));
        assertEquals(ResultCode.BAD_REQUEST, invalidUser.getResultCode());
        assertEquals(ResultCode.BAD_REQUEST, invalidConversation.getResultCode());
    }

    @Test
    void doesNotModifyMessagesOrConversationActivityWhenReading() {
        save(conversation, MessageRole.USER, 1, "已有消息", MessageStatus.COMPLETED);
        var before = conversationRepository.findById(conversation.getId()).orElseThrow().getUpdatedAt();
        var history = messageService.getHistoryForUser("user-1", conversation.getId());
        assertEquals(1, history.size());
        assertEquals(1, messageRepository.count());
        assertEquals(before, conversationRepository.findById(conversation.getId()).orElseThrow().getUpdatedAt());
    }

    private Message save(Conversation target, MessageRole role, long sequence, String content,
                         MessageStatus messageStatus) {
        Message message = new Message(target, role, sequence, content);
        message.setStatus(messageStatus);
        return messageRepository.saveAndFlush(message);
    }
}
