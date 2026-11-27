package com.inshot.hx.myhelper.service;

import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.common.exception.BusinessException;
import com.inshot.hx.myhelper.dto.response.DebugSnapshot;
import com.inshot.hx.myhelper.dto.response.IndexResult;
import com.inshot.hx.myhelper.tools.InspectableSimpleVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** Reads uploaded Markdown documents and indexes their chunks in the configured vector store. */
@Service
@ConditionalOnProperty(prefix = "myhelper.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private static final long MAX_DOCUMENT_BYTES = 4L * 1024 * 1024;
    private static final String SOURCE_METADATA = "source";

    private final VectorStore vectorStore;

    private static final Pattern SECTION_START =
            Pattern.compile("(?m)^(?= {0,3}##[ \\t]+\\S)");

    private final TextSplitter textSplitter = new TextSplitter() {
        @Override
        protected List<String> splitText(String text) {
            var first = SECTION_START.matcher(text);
            if (!first.find()) {
                return text.isBlank() ? List.of() : List.of(text.strip());
            }

            // 跳过第一个词条前的文档简介，每块保留自己的标题和正文。
            return Arrays.stream(SECTION_START.split(text.substring(first.start())))
                    .map(String::strip)
                    .toList();
        }
    };
    /*
      索引 ID 映射：源文件名 -> 文档 ID 列表。用于在上传同名文件时删除旧索引。
        例如：
        {
            "1.md": [
                "id-001",
                "id-002",
                "id-003"
            ],

            "2.md": [
                "id-004",
                "id-005"
            ]
        }
     */
    private final Map<String, List<String>> indexedIdsBySource = new HashMap<>();

    public KnowledgeService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public synchronized IndexResult indexMarkdown(MultipartFile file) {
        //检查文件名和文件大小
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请上传非空 Markdown 文档");
        }
        if (file.getSize() > MAX_DOCUMENT_BYTES) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "Markdown 文档不能超过 4 MB");
        }

        String source = getMarkdownFilename(file.getOriginalFilename());
        // 读取文件内容并创建带 source 元数据的 Document，reader需通过 ByteArrayResource 包装
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            log.warn("读取上传文档失败：source={}, exceptionType={}", source, exception.getClass().getName());
            throw new BusinessException(ResultCode.BAD_REQUEST, "无法读取上传的 Markdown 文档");
        }

        var resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return source;
            }
        };
        var reader = new TextReader(resource);
        reader.getCustomMetadata().put(SOURCE_METADATA, source);
        List<Document> documents = textSplitter.split(reader.read());// 将文档拆分为多个 chunk
        if (documents.isEmpty() || documents.stream().allMatch(document -> document.getText().isBlank())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "Markdown 文档没有可索引的文本");
        }
        // 将新文档索引到向量存储，并删除同名旧文档
        List<String> newIds = documents.stream().map(Document::getId).toList();
        List<String> oldIds = indexedIdsBySource.get(source);//source对应的旧索引ID列表
        try {
            vectorStore.add(documents);//添加新文档
            if (oldIds != null) {
                vectorStore.delete(oldIds);//如果是同名文件，删除旧索引
            }
        } catch (RuntimeException exception) {
            try {
                vectorStore.delete(newIds);
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            log.warn("索引上传文档失败：source={}, chunkCount={}, exceptionType={}， exception={}",
                    source, documents.size(), exception.getClass().getName(),exception.getMessage());
            throw new BusinessException(ResultCode.AI_UNAVAILABLE, "文档索引失败，请检查 embedding 服务配置后重试");
        }
        // 更新索引 ID 映射
        indexedIdsBySource.put(source, newIds);
        return new IndexResult(source, documents.size());
    }

    private static String getMarkdownFilename(String originalFilename) {
        String filename = StringUtils.getFilename(originalFilename);
        if (filename == null || filename.isBlank()
                || !filename.toLowerCase(Locale.ROOT).endsWith(".md")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅支持 .md 格式的 Markdown 文档");
        }
        return filename;
    }

    // 搜索知识库
    public List<Document> search(String query) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(5)//返回最相似的前5个文档
                .similarityThreshold(0.6)//相似度阈值
                .build();
        List<Document> searchRes=vectorStore.similaritySearch(request);
        return searchRes;
    }

    public List<String> searchReturnString(String query){
        List<Document> searchResDocument = search(query);
        List<String> searchResString = new ArrayList<>();
        for(Document doc : searchResDocument){
            String knowledgeContent = doc.getFormattedContent();
            searchResString.add(knowledgeContent);
        }
        return searchResString;
    }

    public synchronized DebugSnapshot getAllDocuments() {
        if (!(vectorStore instanceof InspectableSimpleVectorStore store)) {
            throw new IllegalStateException("当前向量存储不支持直接查看内容");
        }
        return new DebugSnapshot(Map.copyOf(indexedIdsBySource), store.snapshot());
    }

}
