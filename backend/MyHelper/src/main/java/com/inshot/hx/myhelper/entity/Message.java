package com.inshot.hx.myhelper.entity;

import com.inshot.hx.myhelper.entity.converter.MessageRoleConverter;
import com.inshot.hx.myhelper.entity.enums.MessageRole;
import com.inshot.hx.myhelper.entity.enums.MessageStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

@Slf4j
@Getter
@Entity
@Table(name = "message", uniqueConstraints = {
        @UniqueConstraint(name = "uk_message_conversation_sequence", columnNames = {"conversation_id", "`sequence`"})
}, indexes = {
        @Index(name = "idx_message_conversation_created", columnList = "conversation_id, created_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_message_conversation"))
    private Conversation conversation;

    @Convert(converter = MessageRoleConverter.class)
    @Column(name = "role", length = 20, nullable = false)
    private MessageRole role;

    @Setter
    @Column(name = "content", columnDefinition = "LONGTEXT", nullable = false)
    private String content = "";

    /** 会话内序号由业务层分配，唯一约束不等于自动分配或并发控制。 */
    @Column(name = "`sequence`", nullable = false, updatable = false)
    private Long sequence;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private MessageStatus status = MessageStatus.PENDING;

    @Setter
    @Column(name = "model_name", length = 100)
    private String modelName;

    @Setter
    @Column(name = "token_count")
    private Integer tokenCount;

    public Message(Conversation conversation, MessageRole role, long sequence, String content) {
        this.conversation = Objects.requireNonNull(conversation, () -> {
            log.error("创建消息实体失败：所属会话为 null，role={}, sequence={}", role, sequence);
            return "所属会话不能为空";
        });
        this.role = Objects.requireNonNull(role, () -> {
            log.error("创建消息实体失败：消息角色为 null，sequence={}", sequence);
            return "消息角色不能为空";
        });
        if (sequence < 1) {
            log.error("创建消息实体失败：消息序号必须大于 0，role={}, sequence={}", role, sequence);
            throw new IllegalArgumentException("消息序号必须大于 0");
        }
        this.sequence = sequence;
        this.content = Objects.requireNonNull(content, () -> {
            log.error("创建消息实体失败：消息内容为 null，role={}, sequence={}", role, sequence);
            return "消息内容不能为空，可使用空字符串作为回复占位";
        });
        this.status = role == MessageRole.ASSISTANT ? MessageStatus.PENDING : MessageStatus.COMPLETED;
    }
}
