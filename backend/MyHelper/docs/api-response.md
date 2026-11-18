# API 统一返回格式

普通 JSON 接口统一返回以下三个字段，`data` 即使为空也保留：

```json
{
  "code": 0,
  "message": "操作成功",
  "data": { "id": 1 }
}
```

- `code`：业务状态码，`0` 表示成功，非 `0` 表示失败。
- `message`：可公开的提示信息，前端不要依赖此字段判断业务类型。
- `data`：业务数据；无返回数据或失败时为 `null`。

## Controller 使用

```java
import com.inshot.hx.myhelper.common.api.ApiResponse;
import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.common.exception.BusinessException;

// 查询成功（T 替换为实际 DTO 类型）
return ApiResponse.success(dto);

// 删除等无数据操作，默认 HTTP 200，仍有 JSON 响应体
return ApiResponse.success();

// 业务失败：由全局处理器设置 HTTP 404 并生成统一响应
throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在");

// 默认业务失败：HTTP 400，业务码 40001
throw new BusinessException("请等待当前回复完成");
```

Controller 必须显式返回 `ApiResponse<T>`（也可使用 `ResponseEntity<ApiResponse<T>>` 设置 201 等状态）。没有自动包装普通对象，避免影响文件下载、SSE、流式响应和第三方端点。业务错误优先抛出 `BusinessException`，不要直接返回失败对象而遗漏 HTTP 错误状态。

## 状态码约定

| 业务码 | HTTP 状态码 | 含义 |
| --- | --- | --- |
| 0 | 200（或显式设置的其他 2xx） | 成功 |
| 40000 | 400 | 参数缺失、类型错误、校验失败、JSON 不合法 |
| 40001 | 400 | 普通业务失败 |
| 40100 | 401 | 未登录 |
| 40300 | 403 | 无权访问 |
| 40400 | 404 | 资源或路由不存在 |
| 40500 | 405 | 请求方法不支持 |
| 40900 | 409 | 资源状态冲突 |
| 50000 | 500 | 未知服务异常 |
| 50200 | 502 | 大模型请求失败或未返回文本 |

其他 Spring MVC 标准异常使用 `HTTP 状态码 × 100`，例如不支持的媒体类型为 HTTP 415 / `41500`。保留框架设置的响应头，如 HTTP 405 的 `Allow`。未知异常在服务端记录完整堆栈，客户端只收到通用提示，不返回数据库信息、异常类名或堆栈。

`40100`、`40300` 只是响应约定，不提供认证授权能力。全局处理器处理 Spring MVC 中的异常；未来接入 Spring Security 时，过滤器链中的认证/授权失败需要单独接入此结构。代理、Servlet 容器和流式响应已提交之后的错误也不保证能转成此结构。

## 测试

在 JDK 21+ 环境下，从 `backend/MyHelper` 目录执行：

```sh
sh mvnw -Dtest=ApiResponseTest,GlobalExceptionHandlerTest test
```

以上测试不加载完整应用，不需要数据库或 AI 服务配置。参数校验异常已接入全局处理器；若使用 `@Valid` 和 Bean Validation 注解，需另外添加 `spring-boot-starter-validation` 依赖。
