# 模型上下文：消息层

`MessageService.getModelHistory(userId, conversationId, beforeSequence, maxRounds)`
用于取得同一会话最近的完整问答，与前端展示用的 `getHistoryForUser` 分开。

## 调用约定

保存本次用户消息之后，使用返回的 `sequence` 作为独占截止序号：

```java
var history = messageService.getModelHistory(
        userId, conversationId, userMessage.sequence(), 10L);
```

- `beforeSequence` 必须为正整数，只返回序号严格小于它的历史消息。
- `maxRounds` 必须为 1～100；一轮是两条消息，不是单条消息。建议调用方先使用 10 轮。
- 查询前检查用户 ID、会话 ID、会话归属和删除状态。
- 返回 `MessageResponse` 列表，按 `sequence` 升序排列，保留原始文本。
- 不包含本次问题；后续模型调用层应将本次问题追加一次。
- 空会话、第一轮或没有有效轮次时返回空列表，不写消息、不更新会话活动时间。

## 完整轮次规则

在同会话串行发送的前提下，只接受原始消息序列中相邻的 `user → assistant`，
并要求两条消息均为 `COMPLETED` 且内容非空白。
孤立消息、未完成或失败的回复、被其他消息隔开的问答均不配对。
不能先过滤状态再拼接，否则可能把不同轮次的消息错误关联。

数据库仅按序号倒序一次读取最近 200 条原始消息，不过滤角色或状态，
不查询总数。Service 将消息恢复为升序，在内存中检查相邻的完整问答，
最后保留最近 `maxRounds` 轮。
窗口内有效轮次不足时直接返回，不再向前补查。
窗口开头的孤立回复和末尾未回答的问题会被跳过。

## 当前边界

- 本次只实现消息层，没有新增 HTTP 接口，也没有修改 ChatService 或 AiService；
  `/api/messages/send` 尚未将历史传给模型。
- 轮数上限不是 Token 预算。接入模型时还需按模型上下文限制裁剪最旧的完整轮次，
  并为系统提示词、本次输入及模型输出预留空间。
- 固定 200 条是原始消息窗口，不保证一定取得 100 个有效轮次；
  更早的消息即使有效，也不会为了凑足轮数而继续读取。
- 当前表结构没有请求 ID 或回复关联 ID，不能可靠恢复并发交错的问答；
  接入多轮收发前需要保证同会话串行发送。
- `userId` 暂由调用方传入，会话归属检查不等于登录认证。

## 验证

在 `backend/MyHelper` 下运行：

```bash
sh ./mvnw -Dtest=ModelMessageHistoryTest,MessageHistoryTest,MessagePersistenceTest test
```

测试使用 H2，覆盖最近轮次、独占截止、消息顺序、会话隔离、未完成状态、
孤立消息、空白轮次、固定窗口边界、参数及权限校验，以及原有完整历史不受影响。
