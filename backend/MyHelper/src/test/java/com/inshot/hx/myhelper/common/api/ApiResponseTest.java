package com.inshot.hx.myhelper.common.api;

import com.inshot.hx.myhelper.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiResponseTest {

    @Test
    void successContainsData() {
        ApiResponse<String> response = ApiResponse.success("hello");
        assertEquals(0, response.code());
        assertEquals("操作成功", response.message());
        assertEquals("hello", response.data());
    }

    @Test
    void emptySuccessAndFailureContainNullData() {
        assertNull(ApiResponse.success().data());
        ApiResponse<Void> response = ApiResponse.failure(ResultCode.NOT_FOUND);
        assertEquals(40400, response.code());
        assertEquals("请求的资源不存在", response.message());
        assertNull(response.data());
    }

    @Test
    void failureCannotUseSuccessCode() {
        assertThrows(IllegalArgumentException.class, () -> ApiResponse.failure(ResultCode.SUCCESS));
        assertThrows(IllegalArgumentException.class, () -> new BusinessException(ResultCode.SUCCESS));
    }
}
