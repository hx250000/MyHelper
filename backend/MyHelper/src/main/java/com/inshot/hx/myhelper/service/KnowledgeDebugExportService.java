package com.inshot.hx.myhelper.service;

import com.inshot.hx.myhelper.common.api.ApiResponse;
import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.dto.response.DebugSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "myhelper.knowledge",
        name = {"enabled", "upload-enabled"}, havingValue = "true")
public class KnowledgeDebugExportService {
    private static final TypeReference<ApiResponse<DebugSnapshot>> SNAPSHOT_TYPE = new TypeReference<>() {};

    private final KnowledgeService knowledgeService;
    private final JsonMapper jsonMapper;
    private final Path outputPath;

    public KnowledgeDebugExportService(KnowledgeService knowledgeService, JsonMapper jsonMapper,
            @Value("${KNOWLEDGE_DEBUG_OUTPUT_PATH:}") String outputPath) {
        this.knowledgeService = knowledgeService;
        this.jsonMapper = jsonMapper;
        this.outputPath = (outputPath.isBlank() ? defaultOutputPath() : Path.of(outputPath))
                .toAbsolutePath().normalize();
    }

    /** 导出按来源合并；接口响应仍只反映当前进程的向量库。 */
    public synchronized ApiResponse<DebugSnapshot> export() throws IOException {
        DebugSnapshot current = knowledgeService.getAllDocuments();
        DebugSnapshot previous = readSnapshot();
        var idsBySource = new LinkedHashMap<>(previous.indexedIdsBySource());
        var entries = new LinkedHashMap<>(previous.vectorStore());
        current.indexedIdsBySource().forEach((source, ids) -> {
            var oldIds = idsBySource.put(source, ids);
            if (oldIds != null && !oldIds.equals(ids)) {
                oldIds.forEach(entries::remove);
            }
        });
        entries.putAll(current.vectorStore());
        writeSnapshot(ApiResponse.success(new DebugSnapshot(idsBySource, entries)));
        return ApiResponse.success(current);
    }

    private DebugSnapshot readSnapshot() throws IOException {
        try (var input = Files.newInputStream(outputPath)) {
            ApiResponse<DebugSnapshot> stored = jsonMapper.readValue(input, SNAPSHOT_TYPE);
            if (stored == null || stored.code() != ResultCode.SUCCESS.code() || stored.data() == null
                    || stored.data().indexedIdsBySource() == null || stored.data().vectorStore() == null) {
                throw new IOException("知识库调试文件不是有效的快照");
            }
            return stored.data();
        } catch (NoSuchFileException exception) {
            return new DebugSnapshot(Map.of(), Map.of());
        }
    }

    private void writeSnapshot(ApiResponse<DebugSnapshot> response) throws IOException {
        Path parent = Files.createDirectories(outputPath.getParent());
        Path temporary = Files.createTempFile(parent, ".vectorStoreOutput-", ".tmp");
        try {
            jsonMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), response);
            Files.move(temporary, outputPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path defaultOutputPath() {
        Path backend = Path.of("backend", "MyHelper");
        Path relativeOutput = Path.of("docs", "addition", "vectorStoreOutput.md");
        return Files.isRegularFile(backend.resolve("pom.xml")) ? backend.resolve(relativeOutput) : relativeOutput;
    }
}
