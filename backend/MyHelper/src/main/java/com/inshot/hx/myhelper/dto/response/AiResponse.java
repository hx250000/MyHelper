package com.inshot.hx.myhelper.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.Locale;

/** Gemini OpenAI 兼容响应的业务视图，不是原生 generateContent 响应。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiResponse(
        String reply,
        String id,
        String model,
        String finishReason,
        TokenUsage usage) {

    /** response 已由 Service 检查，至少包含一条有效的文本回复。 */
    public static AiResponse from(ChatResponse response) {
        var generation = response.getResult();
        var metadata = response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        String finishReason = generation.getMetadata() == null ? null
                : nullableText(generation.getMetadata().getFinishReason());

        return new AiResponse(
                generation.getOutput().getText(),
                metadata == null ? null : nullableText(metadata.getId()),
                metadata == null ? null : nullableText(metadata.getModel()),
                // Spring AI 将兼容接口的 finish_reason 转成大写枚举，转回接口约定的小写。
                finishReason == null ? null : finishReason.toLowerCase(Locale.ROOT),
                usage == null || usage instanceof EmptyUsage ? null
                        : new TokenUsage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens()));
    }

    private static String nullableText(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 未提供的计数保留 null；totalTokens 使用上游值，不自行累加。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    }
}
