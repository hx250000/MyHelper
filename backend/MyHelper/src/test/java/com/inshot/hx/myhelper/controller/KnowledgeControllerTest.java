package com.inshot.hx.myhelper.controller;

import com.inshot.hx.myhelper.common.exception.GlobalExceptionHandler;
import com.inshot.hx.myhelper.tools.InspectableSimpleVectorStore;
import com.inshot.hx.myhelper.service.KnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeControllerTest {

    private MockMvc mvc;
    private KnowledgeService knowledgeService;

    @BeforeEach
    void setUp() {
        knowledgeService = mock(KnowledgeService.class);
        mvc = mockMvc(knowledgeService, false);
    }

    @Test
    void uploadsMarkdownAndReturnsIndexedChunkCount() throws Exception {
        var file = new MockMultipartFile("file", "guide.md", "text/markdown", "# Setup".getBytes());
        when(knowledgeService.indexMarkdown(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new KnowledgeService.IndexResult("guide.md", 1));

        mvc.perform(multipart("/api/knowledge/documents").file(file)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.source").value("guide.md"))
                .andExpect(jsonPath("$.data.chunkCount").value(1));

        verify(knowledgeService).indexMarkdown(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void doesNotExposeTheIndexWhenDebugIsDisabled() throws Exception {
        mvc.perform(get("/api/knowledge/debug")).andExpect(status().isNotFound());
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void dumpsSourceIdsAndActualVectorStoreEntriesWithoutEmbeddingAgain() throws Exception {
        var model = mock(EmbeddingModel.class);
        when(model.embed(any(Document.class))).thenReturn(new float[] {0.5f, 1f});
        var service = new KnowledgeService(new InspectableSimpleVectorStore(model));
        String markdown = "## TCP\n\n接收窗口限制发送速度。";
        service.indexMarkdown(new MockMultipartFile("file", "guide.md", "text/markdown",
                markdown.getBytes(StandardCharsets.UTF_8)));
        clearInvocations(model);
        String id = service.getAllDocuments().indexedIdsBySource().get("guide.md").getFirst();
        String entry = "$.data.vectorStore['" + id + "']";

        mockMvc(service, true).perform(get("/api/knowledge/debug"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.indexedIdsBySource['guide.md'][0]").value(id))
                .andExpect(jsonPath(entry + ".id").value(id))
                .andExpect(jsonPath(entry + ".text").value(markdown))
                .andExpect(jsonPath(entry + ".metadata.source").value("guide.md"))
                .andExpect(jsonPath(entry + ".embedding[0]").value(0.5));

        verifyNoInteractions(model);
    }

    private static MockMvc mockMvc(KnowledgeService service, boolean debugEnabled) {
        return MockMvcBuilders.standaloneSetup(new KnowledgeController(service, debugEnabled))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
