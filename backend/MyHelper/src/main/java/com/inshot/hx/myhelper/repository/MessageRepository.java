package com.inshot.hx.myhelper.repository;

import com.inshot.hx.myhelper.entity.Message;
import com.inshot.hx.myhelper.entity.enums.MessageRole;
import com.inshot.hx.myhelper.entity.enums.MessageStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByConversation_IdOrderBySequenceAsc(Long conversationId);

    Optional<Message> findByIdAndConversation_IdAndRole(Long id, Long conversationId, MessageRole role);

    boolean existsByReplyTo_Id(Long questionId);

    /** 一次查询同时加载问答，避免窗口将一轮截断或逐条加载问题。 */
    @Query("""
            select reply from Message reply join fetch reply.replyTo question
            where reply.conversation.id = :conversationId
              and question.conversation.id = :conversationId
              and question.sequence < reply.sequence
              and reply.sequence < :beforeSequence
              and question.role = :userRole and reply.role = :assistantRole
              and question.status = :status and reply.status = :status
            order by question.sequence desc
            """)
    List<Message> findModelHistoryReplies(@Param("conversationId") long conversationId,
                                          @Param("beforeSequence") long beforeSequence,
                                          @Param("userRole") MessageRole userRole,
                                          @Param("assistantRole") MessageRole assistantRole,
                                          @Param("status") MessageStatus status, Pageable pageable);

    /** 调用方必须先锁定会话行；MAX 本身不提供并发保护。 */
    @Query("select coalesce(max(m.sequence), 0) from Message m where m.conversation.id = :conversationId")
    long findMaxSequence(@Param("conversationId") Long conversationId);
}
