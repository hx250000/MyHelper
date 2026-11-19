package com.inshot.hx.myhelper.repository;

import com.inshot.hx.myhelper.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.autoconfigure.exclude=",
        "spring.datasource.url=jdbc:h2:mem:user-repository;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// 使用仓库自身的事务，验证保存失败回滚后仍可查询。
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clearUsers() {
        userRepository.deleteAllInBatch();
    }

    @Test
    void savesAssignedIdAndDerivesUsernameQuery() {
        User user = new User("user-1", "alice");
        assertTrue(user.isNew());
        assertFalse(userRepository.existsByUsername("alice"));

        User saved = userRepository.saveAndFlush(user);

        assertSame(user, saved);
        assertFalse(saved.isNew());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
        assertTrue(userRepository.existsByUsername("alice"));
        assertFalse(userRepository.findById("user-1").orElseThrow().isNew());
    }

    @Test
    void canUpdatePreviouslyLoadedUser() {
        userRepository.saveAndFlush(new User("user-1", "alice"));
        User loaded = userRepository.findById("user-1").orElseThrow();
        var createdAt = loaded.getCreatedAt();
        loaded.setUsername("alice-updated");

        userRepository.saveAndFlush(loaded);

        User updated = userRepository.findById("user-1").orElseThrow();
        assertEquals("alice-updated", updated.getUsername());
        assertEquals(createdAt, updated.getCreatedAt());
        assertEquals(1, userRepository.count());
    }

    @Test
    void duplicateUsernameRollsBackAndAllowsFollowupQuery() {
        userRepository.saveAndFlush(new User("user-1", "alice"));

        assertThrows(DataIntegrityViolationException.class,
                () -> userRepository.saveAndFlush(new User("user-2", "alice")));

        assertTrue(userRepository.existsByUsername("alice"));
        assertFalse(userRepository.existsById("user-2"));
        assertEquals(1, userRepository.count());
    }

    @Test
    void idCollisionDoesNotOverwriteExistingUser() {
        User original = new User("user-1", "alice");
        original.setPasswordHash("original-hash");
        userRepository.saveAndFlush(original);
        User collision = new User("user-1", "mallory");
        collision.setPasswordHash("replacement-hash");

        assertThrows(DataIntegrityViolationException.class,
                () -> userRepository.saveAndFlush(collision));

        User preserved = userRepository.findById("user-1").orElseThrow();
        assertEquals("alice", preserved.getUsername());
        assertEquals("original-hash", preserved.getPasswordHash());
        assertFalse(userRepository.existsByUsername("mallory"));
    }
}
