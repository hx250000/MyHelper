# 模型上下文：消息层

`MessageService.getModelHistory(userId, conversationId, beforeSequence, maxRounds)`
用于取得同一会话最近的完整问答，与前端展示用的 `getHistoryForUser` 分开。

## 调用约定

保存本次用户消息之后，使用返回的 `sequence` 作为独占截止序号：

```java
var history = messageService.getModelHistory(
        userId, conversationId, userMessage.sequence(), 10L);
```

- `beforeSequence` 必须为正整数，只返回问答双方序号均严格小于它的轮次。
- `maxRounds` 必须为 1～100；一轮是两条消息。正式聊天入口当前请求最多 15 轮。
- 查询前检查用户 ID、会话 ID、会话归属和删除状态。
- 返回 `MessageResponse` 列表，按提问序号从旧到新排列，每轮先问题、后回复，保留原始文本。
- 不包含本次问题；后续模型调用层应将本次问题追加一次。
- 空会话、第一轮或没有有效轮次时返回空列表，不写消息、不更新会话活动时间。

## 完整轮次规则

`POST /api/chat/send` 用本轮用户消息入库后生成的 `id` 保存回复。`message.reply_to`
是指向 `message.id` 的外键；服务层校验目标是同会话的 `user`，唯一约束保证每条问题最多一条回复。
用户消息和未回填的旧回复保留 `NULL`。模型生成失败时仍只保留本轮问题，不创建假回复。

数据库按 ID 关联问答，一次读取最近 100 个已完成的候选轮次，不查询总数。
Service 排除空白内容后取最近 `maxRounds` 轮，再恢复为提问升序，不足时不再补查。
即使问答写入顺序交错或中间插入其他消息，也不会串轮。没有关联的旧消息仍在页面历史展示，
但不会靠相邻顺序猜配对，因此不进入模型上下文。

## 已有 MySQL 数据库升级

已有环境可在发布前备份并执行 [迁移 SQL](migrations/001-message-reply-to.sql)。
先用 `SHOW CREATE TABLE message` 检查当前表结构，跳过已存在的列或约束。
迁移不推断或回填旧问答；只在来源可确认时另行回填对应 ID。
新环境可由现有 JPA 建表配置创建带约束的表。

## 模型输入预算

模型调用前按估算 token 数裁剪候选历史。`CHAT_MAX_INPUT_TOKENS` 默认为 `8192`，
由应用和部署者按实际模型调整，需为回复、估算误差及协议开销留出余量。
本地估算器用的分词规则不一定与上游模型一致，不是模型的精确计数，也不会修改模型输出上限。

- 优先保留完整的本次问题。问题本身超限时返回 `400`，正式聊天不会先保存消息；调试入口同样校验。
- 从最新历史往前按完整问答占用预算，第一轮放不下就停止，不跨越它拼接更旧的轮次。
- 选中的历史按时间顺序放入请求；历史都放不下时只发本次问题。裁剪不影响数据库和页面历史。

## Token 用量日志

后端控制台搜索 `chat_token_usage`（`INFO`）。正式聊天和 `/api/ai/chat/nomemory` 共用该日志；
每次实际模型调用记录一条，调用前被拦截的请求不算。日志只记录状态、模型、耗时和计数，不记录问题、历史、参考资料或回复正文。

- `promptTokens`、`completionTokens`、`totalTokens` 是模型响应提供的用量，未提供时记为 `null`。本日志不改变消息表的 `token_count` 口径。
- `estimatedQuestionTokens`、`estimatedHistoryTokens`、`estimatedKnowledgeTokens` 相加等于 `estimatedInputTokens`。历史只计实际发送的消息；知识库部分包含模板、来源标记和分隔符，是拼入资料后相对原问题的增量。
- `candidateHistoryMessages` 和 `selectedHistoryMessages` 分别是裁剪前、实际发送的历史消息数；`retrievedChunks` 和 `includedChunks` 分别是命中、实际拼入的片段数。
- `status` 为 `success`、`invalid_response` 或 `upstream_error`。无有效回复但上游返回了用量时仍会记录。此日志不包含 embedding 开销。

## 当前边界

- 不改变 `/api/chat/send` 和 `/api/messages/history` 的响应结构或前端展示顺序。
- 明确配对不能替代并发控制。当前的整轮发送限制仍只在同一后端实例内有效，
  也不提供客户端请求幂等。
- `userId` 暂由调用方传入，会话归属检查不等于登录认证。

## 验证

在 `backend/MyHelper` 下运行：

```bash
sh ./mvnw -Dtest=AiTokenLoggingTest,ChatServiceTest,ModelMessageHistoryTest,MessageHistoryTest,MessagePersistenceTest,AiChatIntegrationTest test
```

测试使用 H2 和本地模拟模型，覆盖交错写入、独占截止、会话隔离、空白和未完成轮次、
非法或重复关联、数据库约束，以及页面完整历史不受影响。
