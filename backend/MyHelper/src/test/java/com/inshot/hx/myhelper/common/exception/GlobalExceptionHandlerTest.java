package com.inshot.hx.myhelper.common.exception;

import com.inshot.hx.myhelper.common.api.ApiResponse;
import com.inshot.hx.myhelper.common.api.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 独立 MVC 测试，不需要数据库、AI 密钥或启动完整应用。 */
class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void successUsesUnifiedEnvelope() throws Exception {
        mvc.perform(get("/test/success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.name").value("MyHelper"));
    }

    @Test
    void emptySuccessKeepsDataField() throws Exception {
        mvc.perform(get("/test/empty"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$", hasKey("data")))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void businessExceptionKeepsStatusAndPublicMessage() throws Exception {
        mvc.perform(get("/test/business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.message").value("会话不存在"))
                .andExpect(jsonPath("$", hasKey("data")))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void unexpectedExceptionDoesNotExposeInternalDetails() throws Exception {
        mvc.perform(get("/test/error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50000))
                .andExpect(jsonPath("$.message").value("服务异常，请稍后重试"));
    }

    @Test
    void missingAndInvalidParametersUseBadRequestEnvelope() throws Exception {
        mvc.perform(get("/test/parameter"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(get("/test/parameter").param("id", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void malformedAndMissingJsonUseBadRequestEnvelope() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void unsupportedMethodKeepsAllowHeader() throws Exception {
        mvc.perform(post("/test/success"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.code").value(40500));
    }

    @Test
    void unmappedRouteUsesNotFoundEnvelope() throws Exception {
        mvc.perform(get("/test/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void unsupportedMediaTypeUsesUnifiedEnvelope() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(41500));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/success")
        ApiResponse<Map<String, String>> success() {
            return ApiResponse.success(Map.of("name", "MyHelper"));
        }

        @GetMapping("/test/empty")
        ApiResponse<Void> empty() {
            return ApiResponse.success();
        }

        @GetMapping("/test/business")
        ApiResponse<Void> business() {
            throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在");
        }

        @GetMapping("/test/error")
        ApiResponse<Void> error() {
            throw new IllegalStateException("database-password=secret");
        }

        @GetMapping("/test/parameter")
        ApiResponse<Integer> parameter(@RequestParam("id") Integer id) {
            return ApiResponse.success(id);
        }

        @PostMapping(value = "/test/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        ApiResponse<Map<String, Object>> body(@RequestBody Map<String, Object> body) {
            return ApiResponse.success(body);
        }
    }
}
