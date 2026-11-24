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

        /** 按时间顺序由老到新截取历史对话记录 */
    public List<Message> fitHistory(List<Message> history, int questionTokens) {
        if (history.size() % 2 != 0) {
            throw new IllegalArgumentException("模型历史必须由完整问答组成");
        }

        int remaining = maxInputTokens - questionTokens;
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
        return history.subList(start, history.size());
    }

    private int estimateMessage(String content) {
        return estimator.estimate(content) + PER_MESSAGE_OVERHEAD;
    }
}
