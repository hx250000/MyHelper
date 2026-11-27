package com.inshot.hx.myhelper.tools;

import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.common.exception.BusinessException;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/** 限制单次访问token使用量 */
@Component
public class ChatContextBudget {

    //估算每条消息固定开销约为4个token
    private static final int PER_MESSAGE_OVERHEAD = 4;
    private final int maxInputTokens;

    private final JTokkitTokenCountEstimator estimator = new JTokkitTokenCountEstimator();

    public ChatContextBudget(@Value("${myhelper.chat.max-input-tokens}") int maxInputTokens) {
        if (maxInputTokens <= PER_MESSAGE_OVERHEAD) {
            throw new IllegalArgumentException("myhelper.chat.max-input-tokens 必须大于 " + PER_MESSAGE_OVERHEAD);
        }
        this.maxInputTokens = maxInputTokens;
    }

    public int requireQuestionFits(String question) {
        int tokens = estimateMessage(question);
        if (tokens > maxInputTokens) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "本次问题超出模型输入预算，请缩短后重试");
        }
        return tokens;
    }

    /** 保留预算内最近的完整问答，返回顺序由老到新。 */
    public HistorySelection fitHistory(List<Message> history, int currentMessageTokens) {
        if (history.size() % 2 != 0) {
            throw new IllegalArgumentException("模型历史必须由完整问答组成");
        }

        int initialBudget = remainAfter(currentMessageTokens);
        int remaining = initialBudget;
        int start = history.size();
        while (start >= 2) {
            int turnTokens = estimateMessage(history.get(start - 2).getText())
                    + estimateMessage(history.get(start - 1).getText());
            if (turnTokens > remaining) {
                break;
            }
            remaining -= turnTokens;
            start -= 2;
        }
        return new HistorySelection(history.subList(start, history.size()), initialBudget - remaining);
    }

    public record HistorySelection(List<Message> messages, int estimatedTokens) {
    }

    public int estimateMessage(String content) {
        return estimator.estimate(content) + PER_MESSAGE_OVERHEAD;
    }

    public int remainAfter(int token) {
        return maxInputTokens - token;
    }
}
