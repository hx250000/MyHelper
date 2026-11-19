# JPA 实体映射

实体位于 `com.inshot.hx.myhelper.entity`，本次只实现数据模型，不实现 Repository、Service、接口或用户认证。

## 实体与关联

| 实体 | 数据表 | 说明 |
| --- | --- | --- |
| User | user | 最简用户归属；ID 为调用方提供的 1～16 字符字符串，用户名唯一 |
| Conversation | conversation | 多对一关联 User，包含标题、模型名称和软删除标记 |
| Message | message | 多对一关联 Conversation，保存角色、内容、序号和状态 |
| AiGeneration | ai_generation | 多对一关联 Conversation，一对一关联 assistant Message |

按数据库文档最新字段表，`user.id` 和 `conversation.user_id` 使用 `VARCHAR(16)`；文档早期 ER 图及 BIGINT 用户主键说明尚未同步。其他实体使用 `Long` 主键和 `GenerationType.IDENTITY` 自增。

所有关联显式使用懒加载，未配置级联持久化或级联删除。先保存 User，再保存 Conversation、Message 和 AiGeneration。暂不在父实体中维护消息集合，后续通过 Repository 分页查询，避免一次加载全部历史及双向关联问题。

`user` 表和 `sequence` 列在映射中显式引用标识符，避免数据库关键字冲突。数据库约束包括用户名唯一、会话内消息序号唯一、每条 AI 回复至多关联一个生成任务，以及必要的非空和外键约束。

## 构造示例

```java
User user = new User("local-user", "local");
Conversation conversation = new Conversation(user);
Message question = new Message(conversation, MessageRole.USER, 1, "你好");
Message reply = new Message(conversation, MessageRole.ASSISTANT, 2, "");
AiGeneration generation = new AiGeneration(reply);
```

用户消息默认 `COMPLETED`，AI 回复及生成任务默认 `PENDING`。消息角色使用转换器保存为小写的 `user/assistant/system/tool`，状态按枚举名称保存，不使用枚举序号。

`User`、`Conversation`、`Message` 的创建及更新时间由 `BaseTimeEntity` 的 JPA 回调维护；`AiGeneration` 单独维护创建时间，开始及结束时间由后续业务逻辑设置。

## 后续业务层需要完成的行为

- 默认用户初始化：本次没有自动插入用户数据，也没有注册/登录逻辑。
- 更新会话活跃时间：消息写入不会自动触发父会话的 `@PreUpdate`，发送消息时需要显式更新会话。
- 软删除：`deleted` 只是字段，Repository 查询必须显式过滤，不能直接调用物理删除接口。
- 并发控制：消息序号由业务层在事务中分配，唯一索引不能代替“生成期间禁止再次发送”的校验与并发控制。
- 生成状态：Message 和 AiGeneration 状态、执行时间的同步更新，由业务层在短事务中完成，模型调用应在事务外执行。
- Entity 不直接作为 HTTP 响应，使用 DTO，避免暴露密码哈希和触发懒加载。

## 测试和数据库连接

```sh
sh mvnw -Dtest=EntityMappingTest test
```

测试使用 test scope 的 H2 内存数据库及 MySQL 兼容模式，验证 JPA 建表、关联读写、角色转换、长文本、审计时间、唯一及外键约束；不需要 MySQL 或真实模型密钥。H2 测试不替代实际 MySQL 环境验收。

当前 `application.yml` 仍禁用数据源自动配置，以保持现有单轮 AI 接口的启动方式。本次没有启用数据库连接。后续正式接入时需要添加 MySQL JDBC 驱动，移除 `DataSourceAutoConfiguration` 排除项，配置数据源，并使用迁移脚本建表或在本地开发环境明确启用 Hibernate 建表策略。
