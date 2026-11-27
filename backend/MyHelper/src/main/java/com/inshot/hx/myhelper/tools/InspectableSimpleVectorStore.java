package com.inshot.hx.myhelper.tools;

import com.inshot.hx.myhelper.dto.response.DebugSnapshot;
import org.springframework.ai.content.Content;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.util.HashMap;
import java.util.Map;

/** Exposes a read-only snapshot of the in-memory store for local debugging. */
public class InspectableSimpleVectorStore extends SimpleVectorStore {

    public InspectableSimpleVectorStore(EmbeddingModel embeddingModel) {
        super(SimpleVectorStore.builder(embeddingModel));
    }

    public Map<String, DebugSnapshot.Entry> snapshot() {
        Map<String, ? extends Content> contents = this.store;
        Map<String, DebugSnapshot.Entry> snapshot = new HashMap<>();
        contents.forEach((id, content) ->
                snapshot.put(id, new DebugSnapshot.Entry(id, content.getText(), content.getMetadata())));
        return Map.copyOf(snapshot);
    }
}
