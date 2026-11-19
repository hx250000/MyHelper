package com.inshot.hx.myhelper.controller;

import com.inshot.hx.myhelper.common.api.ApiResponse;
import com.inshot.hx.myhelper.dto.request.CreateConversationRequest;
import com.inshot.hx.myhelper.dto.response.ConversationListResponse;
import com.inshot.hx.myhelper.dto.response.ConversationResponse;
import com.inshot.hx.myhelper.service.ConversationService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversation")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping(value = "/new",consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
            )
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConversationResponse> createConversation(
            @RequestBody CreateConversationRequest request) {
        return ApiResponse.success(conversationService.createConversation(request));
    }
    
    @GetMapping("/my") 
    public ApiResponse<ConversationListResponse> getMyConversation(@RequestParam String userId) {
        return ApiResponse.success(conversationService.getMyConversation(userId));
    }
    
}
