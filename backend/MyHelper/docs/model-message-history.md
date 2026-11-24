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

## 当前边界

- 不改变 `/api/chat/send` 和 `/api/messages/history` 的响应结构或前端展示顺序。
- 轮数上限不是 Token 预算。需要时可按模型上下文限制裁剪最旧的完整轮次，
  并为系统提示词、本次输入及模型输出预留空间。
- 明确配对不能替代并发控制。当前的整轮发送限制仍只在同一后端实例内有效，
  也不提供客户端请求幂等。
- `userId` 暂由调用方传入，会话归属检查不等于登录认证。

## 验证

在 `backend/MyHelper` 下运行：

```bash
sh ./mvnw -Dtest=ChatServiceTest,ModelMessageHistoryTest,MessageHistoryTest,MessagePersistenceTest,AiChatIntegrationTest test
```

测试使用 H2 和本地模拟模型，覆盖交错写入、独占截止、会话隔离、空白和未完成轮次、
非法或重复关联、数据库约束，以及页面完整历史不受影响。
