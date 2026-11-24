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
        // 插入顺序不等于 sequence 顺序；问答按序号排序，不按主键排序。
        var second = save(MessageRole.USER, 30, "  第二问\n代码  ", MessageStatus.COMPLETED);
        saveReply(second, 40, "第二答", MessageStatus.COMPLETED);
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
    void pairsInterleavedWritesByQuestionIdAndOrdersByQuestionNotReply() {
        var first = messageService.saveUserMessage("user-1", conversation.getId(), "第一问");
        var second = messageService.saveUserMessage("user-1", conversation.getId(), "第二问");
        var firstReply = messageService.saveAssistantMessage("user-1", conversation.getId(), first.id(),
                "第一答", null, null);
        var secondReply = messageService.saveAssistantMessage("user-1", conversation.getId(), second.id(),
                "第二答", null, null);
        assertEquals(List.of(first.id(), firstReply.id(), second.id(), secondReply.id()),
                history(5, 2).stream().map(MessageResponse::id).toList());
        assertEquals(List.of(second.id(), secondReply.id()),
                history(5, 1).stream().map(MessageResponse::id).toList());
        assertEquals(List.of(first.id(), firstReply.id()),
                history(4, 2).stream().map(MessageResponse::id).toList());

        var third = save(MessageRole.USER, 5, "第三问", MessageStatus.COMPLETED);
        var fourth = save(MessageRole.USER, 6, "第四问", MessageStatus.COMPLETED);
        saveReply(fourth, 7, "第四答", MessageStatus.COMPLETED);
        saveReply(third, 8, "第三答", MessageStatus.COMPLETED);
        assertEquals(List.of(5L, 8L, 6L, 7L), sequences(history(9, 2)));
    }

    @Test
    void isolatesConversationsAndDoesNotChangeDisplayHistoryOrActivity() {
        saveRound(1, 2);
        save(MessageRole.USER, 3, "未回答的问题", MessageStatus.COMPLETED);
        var other = conversationRepository.saveAndFlush(
                new Conversation(userRepository.findById("user-1").orElseThrow()));
        var otherQuestion = messageRepository.saveAndFlush(new Message(other, MessageRole.USER, 1, "其他会话的问题"));
        var otherReply = new Message(other, MessageRole.ASSISTANT, 2, "其他会话的回复");
        otherReply.setReplyTo(otherQuestion);
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
    void limitsHistoryToLatestCompleteRounds() {
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
            var question = save(MessageRole.USER, sequence++, "问题", MessageStatus.COMPLETED);
            saveReply(question, sequence++, "未完成的回复", status);
            var unfinished = save(MessageRole.USER, sequence++, "未完成的用户消息", status);
            saveReply(unfinished, sequence++, "回复", MessageStatus.COMPLETED);
        }
        saveRound(sequence, sequence + 1);

        assertEquals(List.of(1L, 2L, sequence, sequence + 1), sequences(history(sequence + 2, 10)));
    }

    @Test
    void excludesUnlinkedRepliesButAcceptsLinkedRoundsWithToolMessagesBetween() {
        save(MessageRole.ASSISTANT, 1, "孤立回复", MessageStatus.COMPLETED);
        var failed = save(MessageRole.USER, 2, "失败的问题", MessageStatus.COMPLETED);
        saveReply(failed, 3, "失败回复", MessageStatus.FAILED);
        save(MessageRole.ASSISTANT, 4, "不能越过失败回复配对", MessageStatus.COMPLETED);
        var linked = save(MessageRole.USER, 5, "被工具消息隔开", MessageStatus.COMPLETED);
        save(MessageRole.TOOL, 6, "工具结果", MessageStatus.COMPLETED);
        saveReply(linked, 7, "工具回复", MessageStatus.COMPLETED);
        save(MessageRole.USER, 8, "被系统消息隔开", MessageStatus.COMPLETED);
        save(MessageRole.SYSTEM, 9, "系统消息", MessageStatus.COMPLETED);
        save(MessageRole.ASSISTANT, 10, "系统后的回复", MessageStatus.COMPLETED);
        save(MessageRole.USER, 11, "未回答的旧问题", MessageStatus.COMPLETED);
        saveRound(12, 13);
        save(MessageRole.USER, 14, "本次问题", MessageStatus.COMPLETED);

        assertEquals(List.of(5L, 7L, 12L, 13L), sequences(history(14, 10)));
    }

    @Test
    void skipsBlankRoundsAndSelectsValidRoundsWithinWindow() {
        saveRound(1, 2);
        saveRound(3, 4);
        var valid = save(MessageRole.USER, 5, "有效问题", MessageStatus.COMPLETED);
        saveReply(valid, 6, " \n\t\u3000", MessageStatus.COMPLETED);
        var empty = save(MessageRole.USER, 7, "", MessageStatus.COMPLETED);
        saveReply(empty, 8, "有效回复", MessageStatus.COMPLETED);
        var blank = save(MessageRole.USER, 9, "\n\t", MessageStatus.COMPLETED);
        saveReply(blank, 10, "有效回复", MessageStatus.COMPLETED);

        assertEquals(List.of(1L, 2L, 3L, 4L), sequences(history(11, 2)));
        assertEquals(List.of(3L, 4L), sequences(history(11, 1)));
        assertEquals(10, messageService.getHistoryForUser("user-1", conversation.getId()).size());
    }

    @Test
    void failedRepliesDoNotDisplaceOlderCompleteRounds() {
        saveRound(1, 2);
        for (long sequence = 3; sequence <= 202; sequence += 2) {
            var question = save(MessageRole.USER, sequence, "问题", MessageStatus.COMPLETED);
            saveReply(question, sequence + 1, "", MessageStatus.FAILED);
        }

        assertEquals(List.of(1L, 2L), sequences(history(203, 100)));
        assertEquals(202, messageService.getHistoryForUser("user-1", conversation.getId()).size());
    }

    @Test
    void unansweredQuestionDoesNotCutTheOldestRoundInHalf() {
        for (long sequence = 1; sequence <= 199; sequence += 2) {
            saveRound(sequence, sequence + 1);
        }
        save(MessageRole.USER, 201, "未回答的问题", MessageStatus.COMPLETED);

        var history = history(202, 100);

        assertEquals(200, history.size());
        assertEquals(1L, history.getFirst().sequence());
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
        var question = save(MessageRole.USER, userSequence, "问题" + userSequence, MessageStatus.COMPLETED);
        saveReply(question, assistantSequence, "回复" + assistantSequence, MessageStatus.COMPLETED);
    }

    private Message save(MessageRole role, long sequence, String content, MessageStatus status) {
        var message = new Message(conversation, role, sequence, content);
        message.setStatus(status);
        return messageRepository.saveAndFlush(message);
    }

    private void saveReply(Message question, long sequence, String content, MessageStatus status) {
        var reply = new Message(conversation, MessageRole.ASSISTANT, sequence, content);
        reply.setReplyTo(question);
        reply.setStatus(status);
        messageRepository.saveAndFlush(reply);
    }

    private void assertError(ResultCode code, Runnable action) {
        assertEquals(code, assertThrows(BusinessException.class, action::run).getResultCode());
    }
}
