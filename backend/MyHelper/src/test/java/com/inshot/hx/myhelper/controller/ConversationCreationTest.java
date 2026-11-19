package com.inshot.hx.myhelper.controller;

import com.inshot.hx.myhelper.common.exception.GlobalExceptionHandler;
import com.inshot.hx.myhelper.entity.User;
import com.inshot.hx.myhelper.repository.ConversationRepository;
import com.inshot.hx.myhelper.repository.UserRepository;
import com.inshot.hx.myhelper.service.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真正经过 Controller、Service 事务和 JPA；不连接真实 MySQL 或 AI 服务。 */
@DataJpaTest(properties = {
        "spring.autoconfigure.exclude=",
        "spring.datasource.url=jdbc:h2:mem:conversation-creation;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ConversationService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConversationCreationTest {

    @Autowired
    private ConversationService conversationService;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private UserRepository userRepository;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        conversationRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        User user = new User("user-1", "alice");
        user.setPasswordHash("private-password-hash");
        userRepository.saveAndFlush(user);
        mvc = MockMvcBuilders.standaloneSetup(new ConversationController(conversationService))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void createsAndCommitsConversationWithoutExposingAssociatedUser() throws Exception {
        mvc.perform(post("/api/conversation/new").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":" user-1 "}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.userId").value("user-1"))
                .andExpect(jsonPath("$.data.title").value("新对话"))
                .andExpect(jsonPath("$.data.modelName").value(nullValue()))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.user").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

        assertEquals(1, conversationRepository.count());
        var saved = conversationRepository.findAll().getFirst();
        assertNotNull(saved.getId());
        assertEquals("新对话", saved.getTitle());
        assertNull(saved.getModelName());
        assertFalse(saved.isDeleted());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
        assertEquals(1, userRepository.count());
        assertEquals("private-password-hash",
                userRepository.findById("user-1").orElseThrow().getPasswordHash());
    }

    @Test
    void usesServerDefaultsAndIgnoresClientControlledFields() throws Exception {
        mvc.perform(post("/api/conversation/new").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user-1","id":999999,"deleted":true,
                                 "title":"客户端标题","modelName":"client-model","user":{"id":"other-user"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.title").value("新对话"))
                .andExpect(jsonPath("$.data.modelName").value(nullValue()))
                .andExpect(jsonPath("$.data.userId").value("user-1"));

        var saved = conversationRepository.findAll().getFirst();
        assertNotEquals(999999L, saved.getId());
        assertEquals("新对话", saved.getTitle());
        assertNull(saved.getModelName());
        assertFalse(saved.isDeleted());
    }

    @Test
    void allowsMultipleNewConversationsForSameUser() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/conversation/new").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"user-1\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.title").value("新对话"));
        }
        var saved = conversationRepository.findAll();
        assertEquals(2, saved.size());
        assertNotEquals(saved.get(0).getId(), saved.get(1).getId());
    }

    @Test
    void unknownUserReturnsNotFoundWithoutCreatingAnything() throws Exception {
        mvc.perform(post("/api/conversation/new").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"missing-user\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.message").value("用户不存在"))
                .andExpect(jsonPath("$.data").value(nullValue()));
        assertEquals(0, conversationRepository.count());
        assertEquals(1, userRepository.count());
    }

    @Test
    void rejectsInvalidFieldsAndMalformedBodiesWithoutInserting() throws Exception {
        String[] invalid = {
                "{}", "{\"userId\":null}", "{\"userId\":\"  \"}",
                "{\"userId\":\"" + "u".repeat(17) + "\"}",
                "{", "", "null"
        };
        for (String body : invalid) {
            mvc.perform(post("/api/conversation/new").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000))
                    .andExpect(jsonPath("$.data").value(nullValue()));
        }
        assertEquals(0, conversationRepository.count());
    }

    @Test
    void requiresJsonContentType() throws Exception {
        mvc.perform(post("/api/conversation/new").contentType(MediaType.TEXT_PLAIN)
                        .content("{\"userId\":\"user-1\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(41500));
        assertEquals(0, conversationRepository.count());
    }
}
