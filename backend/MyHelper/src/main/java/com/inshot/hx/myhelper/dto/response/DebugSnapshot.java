package com.inshot.hx.myhelper.dto.response;

import java.util.List;
import java.util.Map;

public record DebugSnapshot(Map<String, List<String>> indexedIdsBySource, Map<String, ?> vectorStore) {
}
