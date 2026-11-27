package com.supportforge.vector;

import com.supportforge.config.QdrantProperties;
import com.supportforge.embedding.EmbeddedKnowledgeChunk;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 用稳定业务身份生成Qdrant Point ID和受控Payload。 */
@Component
public class KnowledgeVectorPointFactory {

    private final QdrantProperties properties;

    public KnowledgeVectorPointFactory(QdrantProperties properties) {
        this.properties = properties;
    }

    public KnowledgeVectorPoint create(
            UUID tenantId,
            UUID documentId,
            UUID documentVersionId,
            String externalDocumentId,
            String category,
            EmbeddedKnowledgeChunk embedded
    ) {
        if (embedded.dimensions() != properties.dimensions()) {
            throw new IllegalArgumentException(
                    "Embedding与Qdrant维度不一致："
                            + embedded.dimensions() + " != "
                            + properties.dimensions()
            );
        }
        String stableIdentity = tenantId + "/" + documentVersionId + "/"
                + embedded.chunk().index() + "/"
                + embedded.chunk().checksumSha256() + "/"
                + properties.indexVersion();
        UUID pointId = UUID.nameUUIDFromBytes(
                stableIdentity.getBytes(StandardCharsets.UTF_8)
        );
        return new KnowledgeVectorPoint(
                pointId,
                tenantId,
                documentId,
                documentVersionId,
                externalDocumentId,
                embedded.chunk().index(),
                embedded.chunk().content(),
                embedded.chunk().checksumSha256(),
                category,
                properties.indexVersion(),
                embedded.model(),
                embedded.modelVersion(),
                embedded.denseVector(),
                true
        );
    }
}
