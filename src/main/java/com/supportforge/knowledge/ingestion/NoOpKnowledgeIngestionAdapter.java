package com.supportforge.knowledge.ingestion;

import com.supportforge.persistence.port.KnowledgeIngestionPersistencePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** JSON/local模式仍执行清洗和分块校验，但不增加新的持久化文件格式。 */
@Component
@ConditionalOnProperty(
        prefix = "supportforge.persistence",
        name = "knowledge",
        havingValue = "json",
        matchIfMissing = true
)
public class NoOpKnowledgeIngestionAdapter
        implements KnowledgeIngestionPersistencePort {

    @Override
    public void replaceChunks(
            String documentId,
            String parserVersion,
            String chunkerVersion,
            List<KnowledgeChunk> chunks
    ) {
    }

    @Override
    public void markFailed(
            String documentId,
            String failureReason
    ) {
    }
}
