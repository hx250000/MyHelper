package com.inshot.hx.myhelper.config;

import com.inshot.hx.myhelper.tools.InspectableSimpleVectorStore;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "myhelper.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeAiConfig {

    @Bean
    VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return new InspectableSimpleVectorStore(embeddingModel);
    }

    @Bean
    ChatClient ragChatClient(ChatClient.Builder builder, VectorStore vectorStore) {
        var ragAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .vectorStore(vectorStore)
                        .similarityThreshold(0.60)
                        .build())
                .build();
        return builder.defaultAdvisors(ragAdvisor).build();
    }
}
