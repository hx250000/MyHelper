package com.inshot.hx.myhelper.service;

import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;
import org.mockito.ArgumentMatchers;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class KnowledgeServiceTest {

    @Test
    void readsAndIndexesUploadedMarkdownWithSourceMetadata() {
        var vectorStore = mock(VectorStore.class);
        var service = new KnowledgeService(vectorStore);
        var file = markdown("guide.md", "# Setup\n\nInstall the application.");

        var result = service.indexMarkdown(file);

        assertEquals("guide.md", result.source());
        assertTrue(result.chunkCount() > 0);
        verify(vectorStore).add(argThat(documents -> documents.stream()
                .anyMatch(document -> document.getText().contains("Install the application.")
                        && "guide.md".equals(document.getMetadata().get("source")))));
    }

    @Test
    void replacesPreviouslyIndexedDocumentWithTheSameFilename() {
        var vectorStore = mock(VectorStore.class);
        var service = new KnowledgeService(vectorStore);

        service.indexMarkdown(markdown("guide.md", "# Old\n\nOld content."));
        service.indexMarkdown(markdown("guide.md", "# New\n\nNew content."));

        verify(vectorStore).delete(ArgumentMatchers.<List<String>>argThat(ids -> !ids.isEmpty()));
    }

    @Test
    void rejectsUnsupportedFilesBeforeCallingTheVectorStore() {
        var vectorStore = mock(VectorStore.class);
        var service = new KnowledgeService(vectorStore);

        var error = assertThrows(BusinessException.class,
                () -> service.indexMarkdown(new MockMultipartFile("file", "notes.txt",
                        "text/plain", "not markdown".getBytes(StandardCharsets.UTF_8))));

        assertEquals(ResultCode.BAD_REQUEST, error.getResultCode());
        verifyNoInteractions(vectorStore);
    }

    @Test
    void rejectsOversizedFilesBeforeCallingTheVectorStore() {
        var vectorStore = mock(VectorStore.class);
        var service = new KnowledgeService(vectorStore);
        var oversized = new byte[4 * 1024 * 1024 + 1];

        var error = assertThrows(BusinessException.class,
                () -> service.indexMarkdown(new MockMultipartFile("file", "large.md",
                        "text/markdown", oversized)));

        assertEquals(ResultCode.BAD_REQUEST, error.getResultCode());
        verifyNoInteractions(vectorStore);
    }

    private static MockMultipartFile markdown(String filename, String content) {
        return new MockMultipartFile("file", filename, "text/markdown",
                content.getBytes(StandardCharsets.UTF_8));
    }
}
