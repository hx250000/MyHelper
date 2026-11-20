package com.inshot.hx.myhelper.service;

import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.common.exception.BusinessException;
import com.inshot.hx.myhelper.dto.response.MessageResponse;
import com.inshot.hx.myhelper.entity.Conversation;
import com.inshot.hx.myhelper.entity.Message;
import com.inshot.hx.myhelper.entity.User;
import com.inshot.hx.myhelper.entity.enums.MessageRole;
import com.inshot.hx.myhelper.entity.enums.MessageStatus;
import com.inshot.hx.myhelper.repository.ConversationRepository;
import com.inshot.hx.myhelper.repository.MessageRepository;
import com.inshot.hx.myhelper.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 使用真实 JPA 查询，验证模型历史与前端完整历史的不同规则。 */
@DataJpaTest(showSql = false, properties = {
        "spring.autoconfigure.exclude=",
        "spring.datasource.url=jdbc:h2:mem:model-message-history;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MessageService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ModelMessageHistoryTest {

    @Autowired
    private MessageService messageService;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private UserRepository userRepository;

    private Conversation conversation;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAllInBatch();
        conversationRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        User user = userRepository.saveAndFlush(new User("user-1", "alice"));
        conversation = conversationRepository.saveAndFlush(new Conversation(user));
    }

    @Test
    void returnsLatestCompleteRoundsInSequenceOrderWithExclusiveCutoff() {
        // 插入顺序不等于 sequence 顺序；序号有间隔也不影响相邻消息配对。
        save(MessageRole.ASSISTANT, 40, "第二答", MessageStatus.COMPLETED);
        save(MessageRole.USER, 30, "  第二问\n代码  ", MessageStatus.COMPLETED);
        saveRound(10, 20);
        saveRound(50, 60);
        saveRound(70, 80);

        var history = history(70, 2);

        assertEquals(List.of(30L, 40L, 50L, 60L), sequences(history));
        assertEquals(List.of("user", "assistant", "user", "assistant"),
                history.stream().map(MessageResponse::role).toList());
        assertEquals("  第二问\n代码  ", history.getFirst().content());
        // 截止序号落在一轮中间时，该轮整体不返回。
        assertEquals(List.of(10L, 20L, 30L, 40L), sequences(history(60, 10)));
    }

    @Test
    void isolatesConversationsAndDoesNotChangeDisplayHistoryOrActivity() {
        saveRound(1, 2);
        save(MessageRole.USER, 3, "未回答的问题", MessageStatus.COMPLETED);
        var other = conversationRepository.saveAndFlush(
                new Conversation(userRepository.findById("user-1").orElseThrow()));
        messageRepository.saveAndFlush(new Message(other, MessageRole.USER, 1, "其他会话的问题"));
        var otherReply = new Message(other, MessageRole.ASSISTANT, 2, "其他会话的回复");
        otherReply.setStatus(MessageStatus.COMPLETED);
        messageRepository.saveAndFlush(otherReply);
        var updatedAt = conversationRepository.findById(conversation.getId()).orElseThrow().getUpdatedAt();

        var history = messageService.getModelHistory(" user-1 ", conversation.getId(), 4L, 10L);

        assertEquals(List.of(1L, 2L), sequences(history));
        assertEquals(conversation.getId(), history.getFirst().conversationId());
        assertEquals(3, messageService.getHistoryForUser("user-1", conversation.getId()).size());
        assertEquals(5, messageRepository.count());
        assertEquals(updatedAt,
                conversationRepository.findById(conversation.getId()).orElseThrow().getUpdatedAt());
    }

    @Test
    void returnsEmptyHistoryForEmptyConversationAndFirstMessage() {
        assertEquals(List.of(), history(1, 10));
        save(MessageRole.USER, 1, "第一问", MessageStatus.COMPLETED);
        assertEquals(List.of(), history(1, 10));
        assertEquals(List.of(), history(2, 10));
    }

    @Test
    void acceptsMaximumWindowAndDropsOnlyTheOldestCompleteRound() {
        for (long sequence = 1; sequence <= 201; sequence += 2) {
            saveRound(sequence, sequence + 1);
        }

        var history = history(203, 100);

        assertEquals(200, history.size());
        assertEquals(3L, history.getFirst().sequence());
        assertEquals(202L, history.getLast().sequence());
        assertEquals(List.of(201L, 202L), sequences(history(203, 1)));
    }

    @Test
    void excludesEveryUnfinishedStatusOnEitherSide() {
        saveRound(1, 2);
        long sequence = 3;
        for (var status : MessageStatus.values()) {
            if (status == MessageStatus.COMPLETED) {
                continue;
            }
            save(MessageRole.USER, sequence++, "问题", MessageStatus.COMPLETED);
            save(MessageRole.ASSISTANT, sequence++, "未完成的回复", status);
            save(MessageRole.USER, sequence++, "未完成的用户消息", status);
            save(MessageRole.ASSISTANT, sequence++, "回复", MessageStatus.COMPLETED);
        }
        saveRound(sequence, sequence + 1);

        assertEquals(List.of(1L, 2L, sequence, sequence + 1), sequences(history(sequence + 2, 10)));
    }

    @Test
    void doesNotPairAcrossOrphansFailedRepliesOrOtherRoles() {
        save(MessageRole.ASSISTANT, 1, "孤立回复", MessageStatus.COMPLETED);
        save(MessageRole.USER, 2, "失败的问题", MessageStatus.COMPLETED);
        save(MessageRole.ASSISTANT, 3, "失败回复", MessageStatus.FAILED);
        save(MessageRole.ASSISTANT, 4, "不能越过失败回复配对", MessageStatus.COMPLETED);
        save(MessageRole.USER, 5, "被工具消息隔开", MessageStatus.COMPLETED);
        save(MessageRole.TOOL, 6, "工具结果", MessageStatus.COMPLETED);
        save(MessageRole.ASSISTANT, 7, "工具回复", MessageStatus.COMPLETED);
        save(MessageRole.USER, 8, "被系统消息隔开", MessageStatus.COMPLETED);
        save(MessageRole.SYSTEM, 9, "系统消息", MessageStatus.COMPLETED);
        save(MessageRole.ASSISTANT, 10, "系统后的回复", MessageStatus.COMPLETED);
        save(MessageRole.USER, 11, "未回答的旧问题", MessageStatus.COMPLETED);
        saveRound(12, 13);
        save(MessageRole.USER, 14, "本次问题", MessageStatus.COMPLETED);

        assertEquals(List.of(12L, 13L), sequences(history(14, 10)));
    }

    @Test
    void skipsBlankRoundsAndSelectsValidRoundsWithinWindow() {
        saveRound(1, 2);
        saveRound(3, 4);
        save(MessageRole.USER, 5, "有效问题", MessageStatus.COMPLETED);
        save(MessageRole.ASSISTANT, 6, " \n\t\u3000", MessageStatus.COMPLETED);
        save(MessageRole.USER, 7, "", MessageStatus.COMPLETED);
        save(MessageRole.ASSISTANT, 8, "有效回复", MessageStatus.COMPLETED);
        save(MessageRole.USER, 9, "\n\t", MessageStatus.COMPLETED);
        save(MessageRole.ASSISTANT, 10, "有效回复", MessageStatus.COMPLETED);

        assertEquals(List.of(1L, 2L, 3L, 4L), sequences(history(11, 2)));
        assertEquals(List.of(3L, 4L), sequences(history(11, 1)));
        assertEquals(10, messageService.getHistoryForUser("user-1", conversation.getId()).size());
    }

    @Test
    void doesNotFetchOlderMessagesWhenWindowContainsOnlyInvalidRounds() {
        saveRound(1, 2);
        for (long sequence = 3; sequence <= 202; sequence += 2) {
            save(MessageRole.USER, sequence, "问题", MessageStatus.COMPLETED);
            save(MessageRole.ASSISTANT, sequence + 1, "", MessageStatus.FAILED);
        }

        assertEquals(List.of(), history(203, 100));
        assertEquals(202, messageService.getHistoryForUser("user-1", conversation.getId()).size());
    }

    @Test
    void skipsOrphanReplyAtWindowStartAndUnansweredQuestionAtWindowEnd() {
        for (long sequence = 1; sequence <= 199; sequence += 2) {
            saveRound(sequence, sequence + 1);
        }
        save(MessageRole.USER, 201, "未回答的问题", MessageStatus.COMPLETED);

        var history = history(202, 100);

        // 最近 200 条为 sequence 2～201；2 是窗口切断的孤立回复，201 尚无回复。
        assertEquals(198, history.size());
        assertEquals(3L, history.getFirst().sequence());
        assertEquals(200L, history.getLast().sequence());
    }

    @Test
    void validatesBoundsAndIdentifiersWithoutOverflow() {
        for (long cutoff : new long[]{0L, -1L}) {
            assertError(ResultCode.BAD_REQUEST,
                    () -> messageService.getModelHistory("user-1", conversation.getId(), cutoff, 10L));
        }
        for (long rounds : new long[]{0L, -1L, 101L, Long.MAX_VALUE}) {
            assertError(ResultCode.BAD_REQUEST,
                    () -> messageService.getModelHistory("user-1", conversation.getId(), 1L, rounds));
        }
        for (String userId : new String[]{null, "", " ", "x".repeat(17)}) {
            assertError(ResultCode.BAD_REQUEST,
                    () -> messageService.getModelHistory(userId, conversation.getId(), 1L, 10L));
        }
        for (long conversationId : new long[]{0L, -1L}) {
            assertError(ResultCode.BAD_REQUEST,
                    () -> messageService.getModelHistory("user-1", conversationId, 1L, 10L));
        }
        assertEquals(List.of(), history(Long.MAX_VALUE, 100));
    }

    @Test
    void rejectsInaccessibleDeletedAndMissingConversations() {
        saveRound(1, 2);
        userRepository.saveAndFlush(new User("user-2", "bob"));
        assertError(ResultCode.FORBIDDEN,
                () -> messageService.getModelHistory("user-2", conversation.getId(), 3L, 10L));
        assertError(ResultCode.NOT_FOUND,
                () -> messageService.getModelHistory("user-1", Long.MAX_VALUE, 3L, 10L));
        conversation.setDeleted(true);
        conversationRepository.saveAndFlush(conversation);
        assertError(ResultCode.NOT_FOUND,
                () -> messageService.getModelHistory("user-1", conversation.getId(), 3L, 10L));
        assertEquals(2, messageRepository.count());
    }

    private List<MessageResponse> history(long beforeSequence, long maxRounds) {
        return messageService.getModelHistory("user-1", conversation.getId(), beforeSequence, maxRounds);
    }

    private List<Long> sequences(List<MessageResponse> history) {
        return history.stream().map(MessageResponse::sequence).toList();
    }

    private void saveRound(long userSequence, long assistantSequence) {
        save(MessageRole.USER, userSequence, "问题" + userSequence, MessageStatus.COMPLETED);
        save(MessageRole.ASSISTANT, assistantSequence, "回复" + assistantSequence, MessageStatus.COMPLETED);
    }

    private void save(MessageRole role, long sequence, String content, MessageStatus status) {
        var message = new Message(conversation, role, sequence, content);
        message.setStatus(status);
        messageRepository.saveAndFlush(message);
    }

    private void assertError(ResultCode code, Runnable action) {
        assertEquals(code, assertThrows(BusinessException.class, action::run).getResultCode());
    }
}
