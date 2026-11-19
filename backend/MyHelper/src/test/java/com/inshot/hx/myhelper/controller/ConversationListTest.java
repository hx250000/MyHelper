package com.inshot.hx.myhelper.controller;

import com.inshot.hx.myhelper.common.exception.GlobalExceptionHandler;
import com.inshot.hx.myhelper.entity.Conversation;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 经过真实 Service/JPA，关闭 Open-in-View 后验证 DTO 在事务外仍可安全序列化。 */
@DataJpaTest(properties = {
        "spring.autoconfigure.exclude=",
        "spring.datasource.url=jdbc:h2:mem:conversation-list;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ConversationService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConversationListTest {

    @Autowired
    private ConversationService conversationService;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private UserRepository userRepository;

    private MockMvc mvc;
    private User user;

    @BeforeEach
    void setUp() {
        conversationRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        user = new User("user-1", "alice");
        user.setPasswordHash("private-password-hash");
        userRepository.saveAndFlush(user);
        mvc = MockMvcBuilders.standaloneSetup(new ConversationController(conversationService))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void returnsUsernameAndOnlyRequestedUsersConversationDtos() throws Exception {
        Conversation first = new Conversation(user);
        first.setTitle("学习计划");
        first.setModelName("test-model");
        conversationRepository.saveAndFlush(first);
        conversationRepository.saveAndFlush(new Conversation(user));
        User other = userRepository.saveAndFlush(new User("user-2", "bob"));
        conversationRepository.saveAndFlush(new Conversation(other));

        mvc.perform(get("/api/conversation/my").param("userId", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.*", hasSize(2)))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.conversations", hasSize(2)))
                .andExpect(jsonPath("$.data.conversations[*].userId",
                        everyItem(is("user-1"))))
                .andExpect(jsonPath("$.data.conversations[?(@.title == '学习计划')].id",
                        contains(first.getId().intValue())))
                .andExpect(jsonPath("$.data.conversations[?(@.title == '学习计划')].modelName",
                        contains("test-model")))
                .andExpect(jsonPath("$.data.conversations[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.conversations[0].updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.conversations[*].user").doesNotExist())
                .andExpect(jsonPath("$.data.conversations[*].deleted").doesNotExist())
                .andExpect(content().string(not(containsString("passwordHash"))))
                .andExpect(content().string(not(containsString("private-password-hash"))));
    }

    @Test
    void returnsUsernameAndEmptyArrayForUserWithoutConversations() throws Exception {
        mvc.perform(get("/api/conversation/my").param("userId", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.conversations").isArray())
                .andExpect(jsonPath("$.data.conversations").isEmpty());
    }

    @Test
    void returnsNotFoundForUnknownUser() throws Exception {
        mvc.perform(get("/api/conversation/my").param("userId", "missing-user"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.message").value("用户不存在"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void requiresUserId() throws Exception {
        mvc.perform(get("/api/conversation/my"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }
}
