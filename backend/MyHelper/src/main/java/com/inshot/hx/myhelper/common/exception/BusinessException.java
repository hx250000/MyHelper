package com.inshot.hx.myhelper.common.exception;

import com.inshot.hx.myhelper.common.api.ResultCode;

import java.util.Objects;

/** message 会返回客户端，只能包含可公开的业务提示。 */
public class BusinessException extends RuntimeException {

    private final ResultCode resultCode;

    public BusinessException(String message) {
        this(ResultCode.BUSINESS_ERROR, message);
    }

    public BusinessException(ResultCode resultCode) {
        this(resultCode, resultCode.message());
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.resultCode = Objects.requireNonNull(resultCode, "resultCode");
        if (!resultCode.httpStatus().isError()) {
            throw new IllegalArgumentException("业务异常必须使用错误状态码");
        }
    }

    public ResultCode getResultCode() {
        return resultCode;
    }
}
