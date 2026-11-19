package com.inshot.hx.myhelper.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Objects;

/** 暂只承载会话归属，不实现认证；ID 由调用方提供，最长 16 字符。 */
@Getter
@Entity
@Table(name = "`user`", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_username", columnNames = "username")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

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
            throw new IllegalArgumentException("用户 ID 必须为 1 到 16 个字符");
        }
        this.id = id;
        this.username = Objects.requireNonNull(username, "用户名不能为空");
    }
}
