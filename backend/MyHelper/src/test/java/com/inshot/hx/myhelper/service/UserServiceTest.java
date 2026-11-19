package com.inshot.hx.myhelper.service;

import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.common.exception.BusinessException;
import com.inshot.hx.myhelper.dto.request.RegisterRequest;
import com.inshot.hx.myhelper.entity.User;
import com.inshot.hx.myhelper.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceTest {

    private UserRepository repository;
    private PasswordEncoder passwordEncoder;
    private UserService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new UserService(repository, passwordEncoder);
    }

    @Test
    void registersWithNormalizedUsernameAndEncodedUntrimmedPassword() {
        String password = " password123 ";
        when(passwordEncoder.encode(password)).thenReturn("encoded-hash");
        when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            assertTrue(user.getId().matches("[A-Za-z0-9_-]{16}"));
            assertTrue(user.isNew());
            assertEquals("alice", user.getUsername());
            assertEquals("encoded-hash", user.getPasswordHash());
            return user;
        });

        var response = service.register(request("  alice  ", password));

        assertEquals("alice", response.username());
        verify(repository).existsByUsername("alice");
        verify(repository).saveAndFlush(any(User.class));
        verify(passwordEncoder).encode(password);
    }

    @Test
    void rejectsInvalidInputBeforeAccessingRepository() {
        RegisterRequest[] invalidRequests = {
                request(null, "password123"), request("  ", "password123"),
                request("a".repeat(65), "password123"), request("alice", null),
                request("alice", "        "), request("alice", "1234567"),
                request("alice", "x".repeat(73)), request("alice", "中".repeat(25)),
                request("alice", "😀".repeat(7))
        };
        for (RegisterRequest request : invalidRequests) {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> service.register(request));
            assertEquals(ResultCode.BAD_REQUEST, exception.getResultCode());
        }
        verifyNoInteractions(repository, passwordEncoder);
    }

    @Test
    void rejectsExistingUsernameWithoutSaving() {
        when(repository.existsByUsername("alice")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.register(request("alice", "password123")));

        assertEquals(ResultCode.CONFLICT, exception.getResultCode());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void translatesConcurrentDuplicateUsernameToConflict() {
        when(repository.existsByUsername("alice")).thenReturn(false, true);
        when(repository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.register(request("alice", "password123")));

        assertEquals(ResultCode.CONFLICT, exception.getResultCode());
        verify(repository, times(2)).existsByUsername("alice");
    }

    @Test
    void doesNotMisreportOtherIntegrityViolationsAsDuplicateUsername() {
        var failure = new DataIntegrityViolationException("primary key collision");
        when(repository.saveAndFlush(any(User.class))).thenThrow(failure);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> service.register(request("alice", "password123"))));
        verify(repository, times(2)).existsByUsername("alice");
    }

    private RegisterRequest request(String username, String password) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }
}
