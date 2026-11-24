package com.inshot.hx.myhelper.common.exception;

import com.inshot.hx.myhelper.common.api.ResultCode;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/** message 会返回客户端，只能包含可公开的业务提示。 */
@Slf4j
public class BusinessException extends RuntimeException {

    private final ResultCode resultCode;

    public BusinessException(String message) {
        this(ResultCode.BUSINESS_ERROR, message);
    }

    public BusinessException(ResultCode resultCode) {
        this(resultCode, resultCode.message());
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(Objects.requireNonNull(message, () -> {
            log.error("构建业务异常失败：异常提示 message 为 null");
            return "message";
        }));
        this.resultCode = Objects.requireNonNull(resultCode, () -> {
            log.error("构建业务异常失败：业务状态码 resultCode 为 null");
            return "resultCode";
        });
        if (!resultCode.httpStatus().isError()) {
            log.error("构建业务异常失败：使用了非错误状态码，code={}, status={}",
                    resultCode.code(), resultCode.httpStatus().value());
            throw new IllegalArgumentException("业务异常必须使用错误状态码");
        }
    }

    public ResultCode getResultCode() {
        return resultCode;
    }
}
