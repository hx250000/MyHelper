package com.inshot.hx.myhelper.controller;

import com.inshot.hx.myhelper.common.api.ApiResponse;
import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.common.exception.BusinessException;
import com.inshot.hx.myhelper.dto.response.DebugSnapshot;
import com.inshot.hx.myhelper.dto.response.IndexResult;
import com.inshot.hx.myhelper.service.KnowledgeDebugExportService;
import com.inshot.hx.myhelper.service.KnowledgeService;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
@ConditionalOnProperty(prefix = "myhelper.knowledge",
        name = {"enabled", "upload-enabled"}, havingValue = "true")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final KnowledgeDebugExportService debugExportService;
    private final boolean debugEnabled;

    public KnowledgeController(KnowledgeService knowledgeService, KnowledgeDebugExportService debugExportService,
            @Value("${KNOWLEDGE_DEBUG_ENABLED:false}") boolean debugEnabled) {
        this.knowledgeService = knowledgeService;
        this.debugExportService = debugExportService;
        this.debugEnabled = debugEnabled;
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<IndexResult> uploadMarkdown(
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(knowledgeService.indexMarkdown(file));
    }

    @GetMapping(value = "/search")
    public ApiResponse<List<Document>> search(String query){
        return ApiResponse.success(knowledgeService.search(query));
    }

    @GetMapping("/debug")
    public ResponseEntity<ApiResponse<DebugSnapshot>> debug() throws IOException {
        if (!debugEnabled) {
            throw new BusinessException(ResultCode.NOT_FOUND, "知识库调试接口未开启");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(debugExportService.export());
    }
}
