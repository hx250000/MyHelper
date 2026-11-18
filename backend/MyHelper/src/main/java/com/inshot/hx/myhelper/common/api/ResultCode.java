package com.inshot.hx.myhelper.common.api;

import org.springframework.http.HttpStatus;

/** code 为业务状态码，httpStatus 为传输层状态码，两者分别使用。 */
public enum ResultCode {
    SUCCESS(0, "操作成功", HttpStatus.OK),
    BAD_REQUEST(40000, "请求参数不合法", HttpStatus.BAD_REQUEST),
    BUSINESS_ERROR(40001, "业务处理失败", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(40100, "请先登录", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(40300, "无权访问", HttpStatus.FORBIDDEN),
    NOT_FOUND(40400, "请求的资源不存在", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(40500, "不支持该请求方法", HttpStatus.METHOD_NOT_ALLOWED),
    CONFLICT(40900, "资源状态冲突", HttpStatus.CONFLICT),
    INTERNAL_SERVER_ERROR(50000, "服务异常，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR),
    AI_UNAVAILABLE(50200, "大模型服务暂不可用", HttpStatus.BAD_GATEWAY);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ResultCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
