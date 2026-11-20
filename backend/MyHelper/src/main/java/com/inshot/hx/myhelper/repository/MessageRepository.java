package com.inshot.hx.myhelper.repository;

import com.inshot.hx.myhelper.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByConversation_IdOrderBySequenceAsc(Long conversationId);

    /** 只取截止序号之前最近的一批原始消息，保留状态与角色，由 Service 配对。 */
    List<Message> findByConversation_IdAndSequenceLessThanOrderBySequenceDesc(
            long conversationId, long beforeSequence, Pageable pageable);

    /** 调用方必须先锁定会话行；MAX 本身不提供并发保护。 */
    @Query("select coalesce(max(m.sequence), 0) from Message m where m.conversation.id = :conversationId")
    long findMaxSequence(@Param("conversationId") Long conversationId);
}
