package com.inshot.hx.myhelper.controller;

import com.inshot.hx.myhelper.common.api.ApiResponse;
import com.inshot.hx.myhelper.common.exception.GlobalExceptionHandler;
import com.inshot.hx.myhelper.dto.response.DebugSnapshot;
import com.inshot.hx.myhelper.dto.response.IndexResult;
import com.inshot.hx.myhelper.service.KnowledgeDebugExportService;
import com.inshot.hx.myhelper.service.KnowledgeService;
import com.inshot.hx.myhelper.tools.InspectableSimpleVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @TempDir
    Path tempDir;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private Path outputPath;
    private MockMvc mvc;
    private KnowledgeService knowledgeService;

    @BeforeEach
    void setUp() {
        outputPath = tempDir.resolve("addition/vectorStoreOutput.md");
        knowledgeService = mock(KnowledgeService.class);
        mvc = mockMvc(knowledgeService, false);
    }

    @Test
    void uploadsMarkdownAndReturnsIndexedChunkCount() throws Exception {
        var file = new MockMultipartFile("file", "guide.md", "text/markdown", "# Setup".getBytes());
        when(knowledgeService.indexMarkdown(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new IndexResult("guide.md", 1));

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
        assertFalse(Files.exists(outputPath));
    }

    @Test
    void dumpsSourceIdsAndEntriesWithoutExposingOrRecomputingEmbeddings() throws Exception {
        var model = mock(EmbeddingModel.class);
        when(model.embed(any(Document.class))).thenReturn(new float[] {0.5f, 1f});
        var service = new KnowledgeService(new InspectableSimpleVectorStore(model));
        String markdown = "## TCP\n\n接收窗口限制发送速度。";
        service.indexMarkdown(new MockMultipartFile("file", "guide.md", "text/markdown",
                markdown.getBytes(StandardCharsets.UTF_8)));
        clearInvocations(model);
        String id = service.getAllDocuments().indexedIdsBySource().get("guide.md").getFirst();
        String entry = "$.data.vectorStore['" + id + "']";

        var result = mockMvc(service, true).perform(get("/api/knowledge/debug"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.indexedIdsBySource['guide.md'][0]").value(id))
                .andExpect(jsonPath(entry + ".id").value(id))
                .andExpect(jsonPath(entry + ".text").value(markdown))
                .andExpect(jsonPath(entry + ".metadata.source").value("guide.md"))
                .andExpect(jsonPath(entry + ".embedding").doesNotHaveJsonPath())
                .andReturn();

        assertEquals(jsonMapper.readTree(result.getResponse().getContentAsByteArray()),
                jsonMapper.readTree(outputPath.toFile()));

        verifyNoInteractions(model);

        when(model.embed("TCP")).thenReturn(new float[] {0.5f, 1f});
        var matches = service.search("TCP");
        assertEquals(1, matches.size());
        assertEquals(id, matches.getFirst().getId());
    }

    @Test
    void replacesAllOldChunksByFilenameAndAppendsNewSourcesAcrossStoreRestarts() throws Exception {
        var initial = knowledgeServiceWithEmbeddings();
        index(initial, "guide.md", "## Old one\n\nFirst chunk.\n\n## Old two\n\nSecond chunk.");
        index(initial, "keep.md", "# Unchanged");
        mockMvc(initial, true).perform(get("/api/knowledge/debug")).andExpect(status().isOk());
        var first = readExport();
        var oldIds = first.indexedIdsBySource().get("guide.md");
        assertEquals(2, oldIds.size());
        String keepId = first.indexedIdsBySource().get("keep.md").getFirst();

        // Simulate a previous export from before the embedding field was removed.
        var legacy = jsonMapper.readTree(outputPath.toFile());
        legacy.path("data").path("vectorStore").path(keepId).asObject().putArray("embedding").add(0.5);
        jsonMapper.writeValue(outputPath.toFile(), legacy);

        var restarted = knowledgeServiceWithEmbeddings();
        index(restarted, "guide.md", "## Updated\n\nOnly new chunk.");
        index(restarted, "new.md", "# New source");
        var restartedMvc = mockMvc(restarted, true);
        restartedMvc.perform(get("/api/knowledge/debug")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.indexedIdsBySource['keep.md']").doesNotHaveJsonPath());
        var merged = readExport();
        assertEquals(Set.of("guide.md", "keep.md", "new.md"), merged.indexedIdsBySource().keySet());
        assertEquals(1, merged.indexedIdsBySource().get("guide.md").size());
        String updatedId = merged.indexedIdsBySource().get("guide.md").getFirst();
        String newId = merged.indexedIdsBySource().get("new.md").getFirst();
        assertEquals(Set.of(keepId, updatedId, newId), merged.vectorStore().keySet());
        assertEquals("## Updated\n\nOnly new chunk.", merged.vectorStore().get(updatedId).text());
        assertEquals(first.vectorStore().get(keepId), merged.vectorStore().get(keepId));
        assertFalse(jsonMapper.readTree(outputPath.toFile()).path("data").path("vectorStore")
                .path(keepId).has("embedding"));

        restartedMvc.perform(get("/api/knowledge/debug")).andExpect(status().isOk());
        assertEquals(merged, readExport());
    }

    @Test
    void leavesAnUnreadableSnapshotUntouchedAndReturnsAnError() throws Exception {
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, "not JSON");
        mockMvc(knowledgeServiceWithEmbeddings(), true)
                .perform(get("/api/knowledge/debug"))
                .andExpect(status().isInternalServerError());
        assertEquals("not JSON", Files.readString(outputPath));
    }

    private DebugSnapshot readExport() {
        return jsonMapper.readValue(outputPath.toFile(), new TypeReference<ApiResponse<DebugSnapshot>>() {}).data();
    }

    private static KnowledgeService knowledgeServiceWithEmbeddings() {
        var model = mock(EmbeddingModel.class);
        when(model.embed(any(Document.class))).thenReturn(new float[] {0.5f, 1f});
        return new KnowledgeService(new InspectableSimpleVectorStore(model));
    }

    private static void index(KnowledgeService service, String filename, String markdown) {
        service.indexMarkdown(new MockMultipartFile("file", filename, "text/markdown",
                markdown.getBytes(StandardCharsets.UTF_8)));
    }

    private MockMvc mockMvc(KnowledgeService service, boolean debugEnabled) {
        var exporter = new KnowledgeDebugExportService(service, jsonMapper, outputPath.toString());
        return MockMvcBuilders.standaloneSetup(new KnowledgeController(service, exporter, debugEnabled))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
