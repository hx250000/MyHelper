package com.inshot.hx.myhelper.controller;

import com.inshot.hx.myhelper.common.api.ApiResponse;
import com.inshot.hx.myhelper.dto.request.SaveAssistantMessageRequest;
import com.inshot.hx.myhelper.dto.request.SaveMessageRequest;
import com.inshot.hx.myhelper.dto.response.MessageResponse;
import com.inshot.hx.myhelper.service.MessageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController 
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping(value = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<MessageResponse>> getHistory(@RequestParam String userId,
                                                        @RequestParam Long conversationId) {
        return ApiResponse.success(messageService.getHistory(userId, conversationId));
    }

    @PostMapping(value = "/user", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MessageResponse> saveUserMessage(@RequestBody SaveMessageRequest request) {
        return ApiResponse.success(messageService.saveUserMessage(request));
    }

    @PostMapping(value = "/assistant", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MessageResponse> saveAssistantMessage(@RequestBody SaveAssistantMessageRequest request) {
        return ApiResponse.success(messageService.saveAssistantMessage(request.getUserId(),
                request.getConversationId(), request.getContent(), request.getModelName(), request.getTokenCount()));
    }
}

