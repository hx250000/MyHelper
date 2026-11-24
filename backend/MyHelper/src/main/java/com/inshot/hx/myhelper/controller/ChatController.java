package com.inshot.hx.myhelper.controller;

import com.inshot.hx.myhelper.common.api.ApiResponse;
import com.inshot.hx.myhelper.dto.request.SendMessageRequest;
import com.inshot.hx.myhelper.dto.response.SendMessageResponse;
import com.inshot.hx.myhelper.service.ChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 正式聊天写入入口；MessageController 只提供历史查询。 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/send", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SendMessageResponse> send(@RequestBody SendMessageRequest request) {
        return ApiResponse.success(chatService.sendMessage(request));
    }
}
