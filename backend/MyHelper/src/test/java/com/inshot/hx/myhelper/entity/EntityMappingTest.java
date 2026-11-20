package com.inshot.hx.myhelper.entity;

import com.inshot.hx.myhelper.entity.converter.MessageRoleConverter;
import com.inshot.hx.myhelper.entity.enums.MessageRole;
import com.inshot.hx.myhelper.entity.enums.MessageStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证实际 JPA 建表和读写；只使用测试内存库，不连接本地 MySQL 或 AI 服务。 */
@DataJpaTest(properties = {
        "spring.autoconfigure.exclude=",
        "spring.datasource.url=jdbc:h2:mem:entity-mapping;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EntityMappingTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    void persistsConversationMessagesWithLazyRelationships() {
        Conversation conversation = persistConversation();
        Message question = new Message(conversation, MessageRole.USER, 1, "你好");
        Message reply = new Message(conversation, MessageRole.ASSISTANT, 2, "");
        entityManager.persist(question);
        entityManager.persist(reply);
        entityManager.flush();

        assertNotNull(conversation.getId());
        assertNotNull(question.getId());
        assertNotNull(reply.getId());
        assertNotNull(reply.getCreatedAt());
        assertEquals(MessageStatus.COMPLETED, question.getStatus());
        assertEquals(MessageStatus.PENDING, reply.getStatus());
        assertFalse(conversation.isDeleted());
        assertNull(conversation.getUser().getPasswordHash());
        entityManager.clear();

        Message loaded = entityManager.find(Message.class, reply.getId());
        assertFalse(entityManager.getEntityManagerFactory().getPersistenceUnitUtil()
                .isLoaded(loaded, "conversation"));
        assertEquals(conversation.getId(), loaded.getConversation().getId());
        assertEquals("", loaded.getContent());
        assertEquals("local-user", loaded.getConversation().getUser().getId());
        assertNotNull(loaded.getCreatedAt());
    }

    @ParameterizedTest
    @EnumSource(MessageRole.class)
    void storesRolesAsLowercaseAndReadsThemBack(MessageRole role) {
        Message message = new Message(persistConversation(), role, 1, "内容");
        entityManager.persist(message);
        entityManager.flush();
        entityManager.clear();

        assertEquals(role.getValue(), entityManager.createNativeQuery(
                        "select role from message where id = :id", String.class)
                .setParameter("id", message.getId()).getSingleResult());
        assertEquals(role, entityManager.find(Message.class, message.getId()).getRole());
    }

    @Test
    void persistsLargeContentAndEveryMessageStatus() {
        Conversation conversation = persistConversation();
        String content = "长回复".repeat(30000);
        long sequence = 1;
        for (MessageStatus status : MessageStatus.values()) {
            Message message = new Message(conversation, MessageRole.ASSISTANT, sequence++, content);
            message.setStatus(status);
            message.setModelName("test-model");
            message.setTokenCount(123);
            entityManager.persist(message);
            entityManager.flush();
            entityManager.clear();

            Message loaded = entityManager.find(Message.class, message.getId());
            assertEquals(content, loaded.getContent());
            assertEquals(status, loaded.getStatus());
            assertEquals(status.name(), entityManager.createNativeQuery(
                            "select status from message where id = :id", String.class)
                    .setParameter("id", message.getId()).getSingleResult());
            assertEquals("test-model", loaded.getModelName());
            assertEquals(123, loaded.getTokenCount());
            conversation = entityManager.find(Conversation.class, conversation.getId());
        }
    }

    @Test
    void updatesAuditTimeAndPreservesMessagesWhenSoftDeleted() {
        Conversation conversation = persistConversation();
        Message message = new Message(conversation, MessageRole.USER, 1, "保留的记录");
        entityManager.persist(message);
        entityManager.flush();
        entityManager.clear();
        Conversation loaded = entityManager.find(Conversation.class, conversation.getId());
        LocalDateTime createdAt = loaded.getCreatedAt();
        assertNotNull(createdAt);
        LocalDateTime oldUpdate = LocalDateTime.of(2000, 1, 1, 0, 0);
        entityManager.createNativeQuery("update conversation set updated_at = :time where id = :id")
                .setParameter("time", oldUpdate).setParameter("id", conversation.getId()).executeUpdate();
        entityManager.clear();

        loaded = entityManager.find(Conversation.class, conversation.getId());
        loaded.setDeleted(true);
        entityManager.flush();
        entityManager.clear();

        loaded = entityManager.find(Conversation.class, conversation.getId());
        assertTrue(loaded.isDeleted());
        assertEquals(createdAt, loaded.getCreatedAt());
        assertTrue(loaded.getUpdatedAt().isAfter(oldUpdate));
        assertNotNull(entityManager.find(Message.class, message.getId()));
    }

    @Test
    void rejectsDuplicateSequenceWithinConversation() {
        Conversation conversation = persistConversation();
        entityManager.persist(new Message(conversation, MessageRole.USER, 1, "第一条"));
        entityManager.flush();
        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(new Message(conversation, MessageRole.ASSISTANT, 1, "重复序号"));
            entityManager.flush();
        });
    }

    @Test
    void allowsSameSequenceInDifferentConversations() {
        Conversation first = persistConversation();
        Conversation second = new Conversation(first.getUser());
        entityManager.persist(second);
        entityManager.persist(new Message(first, MessageRole.USER, 1, "第一会话"));
        entityManager.persist(new Message(second, MessageRole.USER, 1, "第二会话"));
        entityManager.flush();
    }

    @Test
    void rejectsDuplicateUsername() {
        persistConversation();
        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(new User("another-user", "local"));
            entityManager.flush();
        });
    }

    @Test
    void rejectsConversationWithoutExistingUser() {
        User missingUser = entityManager.getReference(User.class, "missing-user");
        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(new Conversation(missingUser));
            entityManager.flush();
        });
    }

    @Test
    void preventsPhysicalConversationDeletionWhenMessagesExist() {
        Conversation conversation = persistConversation();
        entityManager.persist(new Message(conversation, MessageRole.USER, 1, "不能级联误删"));
        entityManager.flush();
        entityManager.clear();
        Conversation loaded = entityManager.find(Conversation.class, conversation.getId());
        assertThrows(PersistenceException.class, () -> {
            entityManager.remove(loaded);
            entityManager.flush();
        });
    }

    @Test
    void rejectsInvalidConstructorArgumentsAndUnknownRoles() {
        Conversation conversation = new Conversation(new User("local-user", "local"));
        assertThrows(IllegalArgumentException.class, () -> new User("x".repeat(17), "local"));
        assertThrows(IllegalArgumentException.class, () -> new User(" ", "local"));
        assertThrows(IllegalArgumentException.class,
                () -> new Message(conversation, MessageRole.USER, 0, "内容"));
        assertThrows(NullPointerException.class, () -> new Conversation(null));
        assertThrows(NullPointerException.class,
                () -> new Message(conversation, MessageRole.USER, 1, null));
        MessageRoleConverter converter = new MessageRoleConverter();
        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToEntityAttribute(null));
        assertThrows(IllegalArgumentException.class, () -> converter.convertToEntityAttribute("unknown"));
    }

    private Conversation persistConversation() {
        User user = new User("local-user", "local");
        entityManager.persist(user);
        Conversation conversation = new Conversation(user);
        entityManager.persist(conversation);
        return conversation;
    }
}
