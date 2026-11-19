package com.inshot.hx.myhelper.service;

import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.common.exception.BusinessException;
import com.inshot.hx.myhelper.dto.request.CreateConversationRequest;
import com.inshot.hx.myhelper.entity.Conversation;
import com.inshot.hx.myhelper.entity.User;
import com.inshot.hx.myhelper.repository.ConversationRepository;
import com.inshot.hx.myhelper.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ConversationServiceTest {

    private ConversationRepository conversationRepository;
    private UserRepository userRepository;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        userRepository = mock(UserRepository.class);
        service = new ConversationService(conversationRepository, userRepository);
    }

    @Test
    void createsNewConversationWithNormalizedUserIdAndServerDefaults() {
        User user = new User("user-1", "alice");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(conversationRepository.saveAndFlush(any(Conversation.class)))
                .thenAnswer(invocation -> {
                    Conversation conversation = invocation.getArgument(0);
                    assertSame(user, conversation.getUser());
                    assertNull(conversation.getId());
                    assertFalse(conversation.isDeleted());
                    assertEquals("新对话", conversation.getTitle());
                    assertNull(conversation.getModelName());
                    return conversation;
                });

        var response = service.createConversation(request(" user-1 ", " 学习计划 ", " test-model "));

        assertEquals("user-1", response.userId());
        assertEquals("新对话", response.title());
        assertNull(response.modelName());
        verify(userRepository).findById("user-1");
        verifyNoMoreInteractions(userRepository);
        verify(conversationRepository).saveAndFlush(any(Conversation.class));
    }

    @Test
    void usesEntityDefaultsForMissingOrBlankOptionalFields() {
        when(userRepository.findById("user-1"))
                .thenReturn(Optional.of(new User("user-1", "alice")));
        when(conversationRepository.saveAndFlush(any(Conversation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        for (String optional : new String[]{null, "", " \t\n"}) {
            var response = service.createConversation(request("user-1", optional, optional));
            assertEquals("新对话", response.title());
            assertNull(response.modelName());
        }
    }

    @Test
    void acceptsMaximumUserIdLength() {
        String userId = "u".repeat(16);
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(new User(userId, "alice")));
        when(conversationRepository.saveAndFlush(any(Conversation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.createConversation(request(userId, null, null));

        assertEquals(userId, response.userId());
        assertEquals("新对话", response.title());
        assertNull(response.modelName());
    }

    @Test
    void rejectsInvalidInputBeforeAccessingRepositories() {
        CreateConversationRequest[] invalid = {
                null, request(null, null, null), request(" \t", null, null),
                request("u".repeat(17), null, null)
        };
        for (CreateConversationRequest request : invalid) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> service.createConversation(request));
            assertEquals(ResultCode.BAD_REQUEST, exception.getResultCode());
        }
        verifyNoInteractions(userRepository, conversationRepository);
    }

    @Test
    void rejectsUnknownUserWithoutSavingConversation() {
        when(userRepository.findById("missing-user")).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createConversation(request("missing-user", null, null)));

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
        assertEquals("用户不存在", exception.getMessage());
        verifyNoInteractions(conversationRepository);
    }

    @Test
    void propagatesPersistenceFailureInsteadOfReturningSuccess() {
        when(userRepository.findById("user-1"))
                .thenReturn(Optional.of(new User("user-1", "alice")));
        var failure = new DataIntegrityViolationException("database failure");
        when(conversationRepository.saveAndFlush(any(Conversation.class))).thenThrow(failure);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> service.createConversation(request("user-1", null, null))));
    }

    private CreateConversationRequest request(String userId, String title, String modelName) {
        CreateConversationRequest request = new CreateConversationRequest();
        request.setUserId(userId);
        request.setTitle(title);
        request.setModelName(modelName);
        return request;
    }
}
