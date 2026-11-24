package com.inshot.hx.myhelper.controller;

import com.inshot.hx.myhelper.common.api.ApiResponse;
import com.inshot.hx.myhelper.dto.response.MessageResponse;
import com.inshot.hx.myhelper.service.MessageService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController 
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    /** 用户获取对话历史消息 */
    @GetMapping(value = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<MessageResponse>> getHistory(@RequestParam String userId,
                                                        @RequestParam Long conversationId) {
        return ApiResponse.success(messageService.getHistoryForUser(userId, conversationId));
    }

}

