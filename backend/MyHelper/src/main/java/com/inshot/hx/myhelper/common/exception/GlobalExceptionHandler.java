package com.inshot.hx.myhelper.common.exception;

import com.inshot.hx.myhelper.common.api.ApiResponse;
import com.inshot.hx.myhelper.common.api.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ResultCode resultCode = exception.getResultCode();
        return ResponseEntity.status(resultCode.httpStatus())
                .body(ApiResponse.failure(resultCode.code(), exception.getMessage()));
    }

    /** Spring MVC 的参数、JSON 解析、路由等异常均保留原始状态码和响应头。 */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String message = messageFor(status.value());
        if (exception instanceof MethodArgumentNotValidException validationException) {
            String details = validationException.getBindingResult().getAllErrors().stream()
                    .map(error -> error.getDefaultMessage() == null ? "参数校验失败" : error.getDefaultMessage())
                    .distinct()
                    .sorted()
                    .collect(Collectors.joining("；"));
            if (!details.isBlank()) {
                message = details;
            }
        }
        if (status.is5xxServerError()) {
            log.error("Spring MVC 请求处理异常", exception);
        }
        return super.handleExceptionInternal(exception,
                ApiResponse.failure(status.value() * 100, message), headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        log.error("未处理的服务异常", exception);
        return ResponseEntity.status(ResultCode.INTERNAL_SERVER_ERROR.httpStatus())
                .body(ApiResponse.failure(ResultCode.INTERNAL_SERVER_ERROR));
    }

    private String messageFor(int status) {
        return switch (status) {
            case 400 -> ResultCode.BAD_REQUEST.message();
            case 401 -> ResultCode.UNAUTHORIZED.message();
            case 403 -> ResultCode.FORBIDDEN.message();
            case 404 -> ResultCode.NOT_FOUND.message();
            case 405 -> ResultCode.METHOD_NOT_ALLOWED.message();
            case 406 -> "不支持请求的响应格式";
            case 409 -> ResultCode.CONFLICT.message();
            case 413 -> "请求内容过大";
            case 415 -> "不支持该请求内容类型";
            default -> status >= 500 ? ResultCode.INTERNAL_SERVER_ERROR.message() : "请求处理失败";
        };
    }
}
