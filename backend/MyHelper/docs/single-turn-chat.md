# 基本单轮对话

## 准备

在 `backend/MyHelper` 下配置 `.env` 中的 `AI_API_KEY`、`AI_BASE_URL`、`AI_MODEL`，以及 MySQL 的 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`，然后运行 `sh ./mvnw spring-boot:run`。

先通过 `POST /api/users/register` 注册用户，再通过 `POST /api/conversation/new`（请求体 `{"userId":"实际用户ID"}`）创建会话。发送时使用返回的用户 ID 和会话 ID。

## 发送并接收回复

```bash
curl -X POST http://localhost:8080/api/messages/send \
  -H 'Content-Type: application/json' \
  -d '{"userId":"实际用户ID","conversationId":1,"content":"你好，请介绍一下自己"}'
```

成功返回 HTTP 200，结构如下（消息对象还包含时间、模型和 Token 等字段）：

```json
{
  "code": 0,
  "message": "操作成功",
  "data": {
    "userMessage": {
      "id": 1,
      "conversationId": 1,
      "role": "user",
      "content": "你好，请介绍一下自己",
      "sequence": 1,
      "status": "COMPLETED"
    },
    "assistantMessage": {
      "id": 2,
      "conversationId": 1,
      "role": "assistant",
      "content": "你好！我是你的 AI 助手。",
      "sequence": 2,
      "status": "COMPLETED"
    }
  }
}
```

`assistantMessage.tokenCount` 保存回复 Token 数（completionTokens）；上游未提供时为 null。
保存后的消息可通过 `GET /api/messages/history?userId=实际用户ID&conversationId=1` 查询。
客户端无需再调用 `/api/messages/user` 或 `/api/messages/assistant`，避免重复保存。

## 当前边界

- 同步、非流式；只发送当前问题给 AI，不附带历史上下文。
- 用户消息非空且最多 10000 字符；不存在、已删除或不属于该用户的会话在调用 AI 前被拒绝。
- 数据库保存使用独立短事务，等待模型期间不占用数据库事务。
- AI 请求失败或返回空文本时返回 HTTP 502；已保存的用户消息保留，不保存虚假回复，也不创建 AI 占位或生成任务。
- 不实现请求去重或同会话并发发送保护。前端调用期间应禁用发送；失败后再次发送会新增用户消息，不应自动重试。
- 尚未接入登录鉴权，`userId` 仍由请求传入；会话归属检查不等于身份认证。
- 前端仍是初始页面，本次仅完成后端消息与 AI 的连接。`POST /api/ai/chat` 仍可用于不入库的模型调试。

## 验证

运行 `sh ./mvnw test`。`AiChatIntegrationTest` 使用 H2 和本地模拟 HTTP 上游，覆盖真实 Spring AI 请求、回复入库、历史查询、单轮上下文、参数及会话权限校验、模型失败；不使用真实密钥或访问真实 AI/MySQL。
