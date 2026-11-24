package com.inshot.hx.myhelper.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 普通 JSON 接口的统一响应；文件下载、SSE 等响应不使用此结构。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(int code, String message, T data) {

    private static final Logger log = LoggerFactory.getLogger(ApiResponse.class);

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ResultCode.SUCCESS.code(), ResultCode.SUCCESS.message(), data);
    }

    public static ApiResponse<Void> success() { //成功响应（无数据）
        return success(null);
    }

    public static ApiResponse<Void> failure(ResultCode resultCode) {
        return failure(resultCode.code(), resultCode.message());
    }

    public static ApiResponse<Void> failure(int code, String message) {
        if (code == ResultCode.SUCCESS.code()) {
            log.error("构建失败响应异常：不能使用成功状态码，code={}", code);
            throw new IllegalArgumentException("失败响应不能使用成功状态码");
        }
        return new ApiResponse<>(code, message, null);
    }
}
