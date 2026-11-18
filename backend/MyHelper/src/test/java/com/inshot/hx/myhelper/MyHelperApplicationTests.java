package com.inshot.hx.myhelper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key",
        "spring.ai.openai.base-url=http://localhost:9999/v1/",
        "spring.ai.openai.chat.model=test-model"
})
class MyHelperApplicationTests {

	@Test
	void contextLoads() {
	}

}
