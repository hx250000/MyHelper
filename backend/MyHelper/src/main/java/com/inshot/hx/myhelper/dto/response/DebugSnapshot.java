package com.inshot.hx.myhelper.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

public record DebugSnapshot(Map<String, List<String>> indexedIdsBySource, Map<String, Entry> vectorStore) {
    @JsonIgnoreProperties("embedding") // 兼容早期带向量的导出文件。
    public record Entry(String id, String text, Map<String, Object> metadata) {
    }
}
