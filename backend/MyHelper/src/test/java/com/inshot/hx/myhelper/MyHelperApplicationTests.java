package com.inshot.hx.myhelper;

import com.inshot.hx.myhelper.service.ChatService;
import com.inshot.hx.myhelper.service.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.datasource.url=jdbc:h2:mem:application-context;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.ai.model.embedding=none",
        "myhelper.knowledge.enabled=false",
        "spring.ai.openai.api-key=test-key",
        "spring.ai.openai.base-url=http://localhost:9999/v1/",
        "spring.ai.openai.chat.model=test-model"
})
class MyHelperApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void loadsChatWithKnowledgeDisabledAndWithoutEmbeddingBeans() {
        assertNotNull(context.getBean(ChatService.class));
        assertTrue(context.getBeansOfType(KnowledgeService.class).isEmpty());
        assertTrue(context.getBeansOfType(VectorStore.class).isEmpty());
        assertTrue(context.getBeansOfType(EmbeddingModel.class).isEmpty());
    }

}
