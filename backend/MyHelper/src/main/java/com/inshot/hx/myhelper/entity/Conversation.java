package com.inshot.hx.myhelper.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

@Slf4j
@Getter
@Entity
@Table(name = "conversation", indexes = {
        @Index(name = "idx_conversation_user_deleted_updated", columnList = "user_id, deleted, updated_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Conversation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_conversation_user"))
    private User user;

    @Setter
    @Column(name = "title", length = 255)
    private String title = "新对话";

    @Setter
    @Column(name = "model_name", length = 100)
    private String modelName;

    /** 仅保存软删除标记，查询过滤及删除行为由后续 Service/Repository 实现。 */
    @Setter
    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    public Conversation(User user) {
        this.user = Objects.requireNonNull(user, () -> {
            log.error("创建会话实体失败：所属用户为 null");
            return "所属用户不能为空";
        });
    }

    /** 消息新增也属于会话活动；仅新增子实体不会触发会话的 PreUpdate。 */
    public void markMessageActivity() {
        updateTimestamp();
    }
}
