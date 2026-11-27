package com.supportforge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 最小启动冒烟测试：证明重命名后的主类、根包扫描和默认配置能共同创建Spring上下文。
 */
@SpringBootTest(properties =

		"supportforge.knowledge.storage-path=target/test-data/${random.uuid}/knowledge.json"
)
class SupportForgeApplicationTests {

	@Test
	void contextLoads() {

	}

}
