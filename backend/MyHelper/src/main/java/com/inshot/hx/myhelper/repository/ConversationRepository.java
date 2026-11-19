package com.inshot.hx.myhelper.repository;

import com.inshot.hx.myhelper.entity.Conversation;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository 
public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    // 这里可以添加自定义的查询方法，例如根据用户ID查询对话列表等
    
    List<Conversation> findConversationByUser_Id(String userid);

    /** 同一会话的消息写入串行分配序号，锁仅在保存事务期间持有。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Conversation c where c.id = :id")
    Optional<Conversation> findByIdForUpdate(@Param("id") Long id);
    
}
