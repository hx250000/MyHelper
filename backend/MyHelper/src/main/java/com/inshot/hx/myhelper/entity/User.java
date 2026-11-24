package com.inshot.hx.myhelper.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Persistable;

import java.util.Objects;

/** 用户资料及会话归属；注册时由服务端生成最长 16 字符的 ID，不实现登录。 */
@Slf4j
@Getter
@Entity
@Table(name = "`user`", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_username", columnNames = "username")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity implements Persistable<String> {

    // ID 由业务代码预先分配，不能仅凭 ID 非空就让 save 走 merge。
    @Transient
    private boolean newEntity = true;

    @Id
    @Column(name = "id", length = 16, nullable = false, updatable = false)
    private String id;

    @Setter
    @Column(name = "username", length = 64, nullable = false)
    private String username;

    @Setter
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    public User(String id, String username) {
        if (id == null || id.isBlank() || id.length() > 16) {
            log.error("创建用户实体失败：用户 ID 为空、空白或超过 16 个字符，idLength={}",
                    id == null ? null : id.length());
            throw new IllegalArgumentException("用户 ID 必须为 1 到 16 个字符");
        }
        this.id = id;
        this.username = Objects.requireNonNull(username, () -> {
            log.error("创建用户实体失败：用户名为 null，userId={}", id);
            return "用户名不能为空";
        });
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostPersist
    @PostLoad
    private void markNotNew() {
        newEntity = false;
    }
}
