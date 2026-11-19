package com.inshot.hx.myhelper.entity;

import com.inshot.hx.myhelper.entity.enums.GenerationStatus;
import com.inshot.hx.myhelper.entity.enums.MessageRole;
import jakarta.persistence.Column;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Objects;

@Getter
@Entity
@Table(name = "ai_generation", uniqueConstraints = {
        @UniqueConstraint(name = "uk_generation_assistant_message", columnNames = "assistant_message_id")
}, indexes = {
        @Index(name = "idx_generation_conversation_status", columnList = "conversation_id, status")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiGeneration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_generation_conversation"))
    private Conversation conversation;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assistant_message_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_generation_assistant_message"))
    private Message assistantMessage;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private GenerationStatus status = GenerationStatus.PENDING;

    @Setter
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Setter
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Setter
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 从回复获取会话，避免构造时把生成任务和回复关联到不同会话。 */
    public AiGeneration(Message assistantMessage) {
        this.assistantMessage = Objects.requireNonNull(assistantMessage, "AI 回复不能为空");
        if (assistantMessage.getRole() != MessageRole.ASSISTANT) {
            throw new IllegalArgumentException("生成任务只能关联 assistant 消息");
        }
        this.conversation = assistantMessage.getConversation();
    }

    @PrePersist
    protected void initializeTimestamp() {
        createdAt = LocalDateTime.now();
    }
}
