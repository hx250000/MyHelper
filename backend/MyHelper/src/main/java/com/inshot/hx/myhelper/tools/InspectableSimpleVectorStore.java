package com.inshot.hx.myhelper.tools;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.util.Map;

/** Exposes a read-only snapshot of the in-memory store for local debugging. */
public class InspectableSimpleVectorStore extends SimpleVectorStore {

    public InspectableSimpleVectorStore(EmbeddingModel embeddingModel) {
        super(SimpleVectorStore.builder(embeddingModel));
    }

    public Map<String, ?> snapshot() {
        return Map.copyOf(this.store);
    }
}
