# 消息保存与后续发送/接收设计

## 已实现：保存用户消息

`POST /api/messages/user`，Content-Type 为 `application/json`。
原 `POST /api/messages` 保留为用户消息保存的兼容入口，请求、响应与行为不变。

```json
{
  "userId": "注册接口返回的用户ID",
  "conversationId": 1,
  "content": "你好"
}
```

成功返回 HTTP 201，响应仍使用统一包装：

```json
{
  "code": 0,
  "message": "操作成功",
  "data": {
    "id": 1,
    "conversationId": 1,
    "role": "user",
    "content": "你好",
    "sequence": 1,
    "status": "COMPLETED",
    "modelName": null,
    "tokenCount": null,
    "createdAt": "2026-11-19T15:00:00",
    "updatedAt": "2026-11-19T15:00:00"
  }
}
```

- 仅保存消息，不调用 AI、不创建 `ai_generation`，不意味着完成一次聊天问答。
- `userId` 必填，去除首尾空白后长度为 1–16；`conversationId` 必须为正整数。
- 会话必须存在、未软删除，且属于传入的用户。不存在/已删除返回 404，归属不符返回 403。
- `content` 不得为 null 或全空白，用户消息最长 10000 个 Java UTF-16 字符；保留原始空白和换行。
- 消息 ID、角色、状态、序号由服务端决定，客户端不能指定模型或 Token 用量。
- 每个会话从 `sequence=1` 开始连续分配序号。保存事务内先锁会话行，再查询最大序号并写入；数据库唯一约束兜底。
- 消息与会话的 `updatedAt` 在同一事务内更新，失败整体回滚；响应只包含 DTO。
- 当前没有幂等键：重复 POST 会产生多条消息，不要把存储接口当作自动重试安全的接口。
- **目前未接入身份认证**：检查传入用户与会话的归属不等于验证调用者身份；公开部署前必须从认证上下文读取用户 ID。

## 已实现：独立保存 AI 回复

`POST /api/messages/assistant`，Content-Type 为 `application/json`：

```json
{
  "userId": "注册接口返回的用户ID",
  "conversationId": 1,
  "content": "你好！有什么可以帮你？",
  "modelName": "test-model",
  "tokenCount": 8
}
```

成功返回 HTTP 201，响应结构与用户消息相同，但 `role` 固定为 `assistant`。
`modelName` 和 `tokenCount` 可省略；角色、状态、序号和消息 ID 不能由客户端指定。
用户和 AI 消息共享会话内连续序号，均使用原有的会话归属校验、软删除拦截和事务逻辑。
两个接口都仅保存消息，不调用模型，不创建生成任务，不改变一问一答或历史查询规则。

**安全边界：当前尚无身份认证，该接口只用于可信开发联调，不验证内容是否真正来自模型。**
公开部署前必须增加身份与调用权限控制，防止任意调用者冒充用户或伪造 AI 回复；
正式聊天流程应由后端调用模型并保存回复，而不是信任浏览器上传的 AI 文本和用量。

后端编排服务可通过注入的 `MessageService` 调用：

```java
messageService.saveAssistantMessage(userId, conversationId, reply, modelName, outputTokens);
```

HTTP 接口复用该方法；消息角色固定为 `assistant`，状态为 `COMPLETED`。
模型名称和 Token 数量可为 null，名称最长 100 个 Java 字符，Token 数量不能为负数。
正文不得为空，不套用用户输入的 10000 字符上限，避免截断正常的模型长回复。
此处 `tokenCount` 应传回复的 output/completion tokens，而不是整次调用的 total tokens。

本层仅负责持久化：暂不强制一问一答、暂不阻止连续保存用户消息，也不管理生成失败/取消。
这些规则应在下面的发送编排层实现，独立保存接口不能替代正式聊天流程。

## 已实现：查询当前会话历史消息

`GET /api/messages/history?userId=user-1&conversationId=1`

返回 HTTP 200，`data` 为 `MessageResponse` 数组；每项字段与保存接口一致。
按会话内 `sequence` 升序排列，同时包含用户消息和 AI 回复，保留正文空白、模型信息及实际消息状态。
空会话返回 `[]`；会话不存在或已软删除返回 404，传入用户与会话归属不符返回 403，参数非法返回 400。
读取使用只读事务，不加保存时的排他锁，也不更新会话活动时间。

目前一次返回该会话全部历史，尚未分页；记录量增加后应改为游标分页，避免大响应。
前端按 `role` 区分用户与 AI 气泡，按 `status` 展示生成中、失败或取消状态。
这是用于展示/恢复的历史，不应原样全量作为模型上下文；模型上下文仍需单独过滤状态、控制 Token 预算。
与保存接口相同，当前显式提供 `userId` 仅用于开发联调，不能替代身份认证。

## 推荐下一步：先做同步发送，再升级 SSE

新增 `POST /api/messages/send`，接收 userId、conversationId、content、clientRequestId。
它统一负责保存和生成，**前端不应先调用保存接口、再调用发送接口**，否则会重复保存。
`clientRequestId` 需要数据库唯一约束以及结果复用逻辑，不能只接收字段却没有去重。

建议拆成三段：

1. **短事务 A：准备本轮。** 校验会话归属并锁定会话；同一会话如果存在 PENDING/RUNNING 任务则返回 409。
   原子地保存 user 消息、assistant 占位消息以及 `ai_generation`，记录请求幂等键。
   user 状态为 COMPLETED，assistant 状态为 PENDING，generation 准备调用时进入 RUNNING。
2. **事务外调用模型。** 按 sequence 读取此前已完成的问答，加上本次用户消息构建 Prompt；
   不包含 assistant 空占位、失败回复或重复的本次输入。按 Token 预算截取最近的完整问答轮次。
   不把数据库锁或事务保持到模型调用结束。
3. **短事务 B：提交结果。** 按 generationId 找到本轮已创建的 assistant 占位并更新，而不是再次追加回复。
   成功时保存内容、模型、completion tokens，assistant/generation 均设为 COMPLETED；
   失败时二者设为 FAILED，保存脱敏错误和完成时间，保留用户输入，解除会话生成中限制。
   若任务已取消/结束则不能被迟到的模型结果覆盖。

当前的 `saveUserMessage` / `saveAssistantMessage` 可验证基础落库，但不能简单地先后调用就视为实现了上述任务状态和幂等。
接入发送时应复用校验和序号分配逻辑，新增原子准备/完成方法；最终回复应更新占位，不再调用追加式 `saveAssistantMessage`。
同步版本可在同一个 HTTP 响应返回 userMessage、assistantMessage、generationId；
前端等待响应即可接收回答，不需要再提供一个让前端提交回复的 `/receive` 接口。

## 后续：流式接收与历史恢复

- SSE 版本可由发送接口创建任务并返回 generationId，再用 `GET /api/generations/{id}/events` 接收
  meta/delta/done/error 事件；先准备任务再订阅也可以使用单个 POST 流式响应配合 fetch 读取。
- SSE 是服务端向浏览器推送，浏览器只展示增量，最终内容和状态始终由后端落库。
- 现有同步 `ChatModel.call()` 需要升级为流式客户端调用；小块内容可以聚合后定期落库，不必每个 token 都更新。
- 订阅端断开是否取消生成需要明确策略；无论哪种策略，都应支持查询任务状态和历史消息来恢复展示。
- 历史查询已按 sequence 排序并保留状态；后续增加游标分页。
- 增加运行超时/重启恢复：将超时的 PENDING/RUNNING 任务转为 FAILED，避免会话永久无法发送。
- 本文中的发送、SSE、幂等及任务生命周期是后续设计，当前尚未实现。

## 验证

```sh
sh mvnw -o -Dtest=MessageHistoryTest,MessagePersistenceTest,ConversationListTest,EntityMappingTest test
```

测试使用内存 H2，不访问真实模型或 MySQL；覆盖用户/AI 消息持久化、正文和元数据校验、
越权和软删除拦截、客户端字段隔离、会话时间更新，以及同会话并发保存的唯一连续序号。
同时覆盖用户/AI 独立保存路径、旧路径兼容、角色固定、AI 可选元数据及参数校验。
历史测试覆盖排序、同用户不同会话隔离、状态保留、空历史、参数校验及只读行为。
悲观锁的实际部署行为仍应在目标 MySQL 上做集成验证。
