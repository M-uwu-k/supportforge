package com.supportforge.vector;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 真实Qdrant模式启动时 fail-fast 创建/校验Collection与Payload索引。 */
@Component
@ConditionalOnProperty(
        prefix = "supportforge.qdrant",
        name = "provider",
        havingValue = "qdrant"
)
public class QdrantIndexBootstrap implements ApplicationRunner {

    private final VectorIndexPort vectorIndex;

    public QdrantIndexBootstrap(VectorIndexPort vectorIndex) {
        this.vectorIndex = vectorIndex;
    }

    @Override
    public void run(ApplicationArguments args) {
        vectorIndex.ensureReady();
    }
}
