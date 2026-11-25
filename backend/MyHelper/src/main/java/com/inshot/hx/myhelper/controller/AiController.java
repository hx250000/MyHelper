package com.inshot.hx.myhelper.controller;

import com.inshot.hx.myhelper.common.api.ApiResponse;
import com.inshot.hx.myhelper.dto.request.AiRequest;
import com.inshot.hx.myhelper.dto.response.AiResponse;
import com.inshot.hx.myhelper.service.AiService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

//Ai单独测试接口
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    //AI测试用接口，实际在chatController
    @PostMapping(value = "/chat/nomemory", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<AiResponse> chat(@RequestBody AiRequest request) {
        return ApiResponse.success(aiService.chat_no_history(request.message()));
    }

}
