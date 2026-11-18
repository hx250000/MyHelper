# MyHelper 个人助手

依据 [个人助手 agent 技术方案](./个人助手agent技术方案.md) 搭建的前后端分离项目目录。

- 后端：Java、Spring AI、Spring Data JPA。
- 前端：Vue。
- 数据库：MySQL。

已实现最小后端大模型接口：接收一条消息 → Spring AI 调用 Gemini → 返回文本。当前为单轮、非流式问答，不保存历史、不依赖数据库，前端保持原状。

启动、密钥配置和接口示例见 [大模型交互说明](backend/MyHelper/docs/ai-chat.md)。实际后端工程位于 `backend/MyHelper`，前端工程位于 `frontend/MyHelper`；下方目录与模块约定保留为后续功能规划。

## 目录结构

```text
MyHelper/
├── backend/                          # Java 后端
│   └── src/
│       ├── main/
│       │   ├── java/com/myhelper/     # 后端基础包（暂定）
│       │   │   ├── config/           # Spring、AI、数据库等配置
│       │   │   ├── controller/       # 用户、会话、消息 HTTP 接口
│       │   │   ├── dto/              # 请求与响应数据对象
│       │   │   ├── entity/           # 用户、会话、消息 JPA 实体
│       │   │   ├── repository/       # Spring Data JPA 数据访问
│       │   │   └── service/          # 用户、会话、消息业务逻辑
│       │   │       └── ai/           # 历史上下文构建、模型调用与回复处理
│       │   └── resources/
│       │       └── prompts/          # 提示词模板
│       └── test/
│           ├── java/com/myhelper/    # 后端测试
│           └── resources/            # 测试配置和数据
├── frontend/                         # Vue 前端
│   ├── public/                       # 无需构建处理的静态资源
│   ├── src/
│   │   ├── api/                      # 用户、会话、消息接口请求
│   │   ├── assets/                   # 图片、图标等资源
│   │   ├── components/               # 公共组件
│   │   ├── layouts/                  # 页面布局
│   │   ├── router/                   # 路由
│   │   ├── stores/                   # 用户、会话和消息处理状态
│   │   ├── styles/                   # 全局样式
│   │   └── views/
│   │       ├── user/                 # 用户管理页面
│   │       └── chat/                 # 会话列表、聊天及消息历史页面
│   └── tests/                        # 前端测试
├── .gitignore
├── README.md
└── 个人助手agent技术方案.md
```

## 模块约定

| 模块 | 后端归属 | 前端归属 |
| --- | --- | --- |
| 用户管理 | `controller`、`service`、`repository`、`entity` | `views/user`、`api`、`stores` |
| 会话管理 | 上述分层，负责创建、列表、查询和删除会话 | `views/chat`、`api`、`stores` |
| 消息管理 | 上述分层，负责发送、回复、存储、历史查询和处理状态 | `views/chat`、`components`、`api`、`stores` |
| AI 服务 | `service/ai`、`config`、`resources/prompts` | 通过消息接口交互 |

后续实现遵循技术方案：一个会话包含多条消息，消息角色为 `user` 或 `assistant`；用户消息与 AI 回复建立对应关系，保存消息处理状态，回复完成后才能发送下一条消息。MySQL 保存业务数据，AI 短期记忆从会话历史消息构建，暂不引入长期记忆模块。
