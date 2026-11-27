package com.supportforge.kafka;

import com.supportforge.indexing.KnowledgeIndexPermanentException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeIndexMessageTest {

    @Test
    void parsesVersionedOutboxEnvelope() {
        UUID event = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        String json = """
                {
                  "event_id": "%s",
                  "payload": {
                    "tenant_id": "%s",
                    "document_id": "%s",
                    "document_version_id": "%s",
                    "external_document_id": "refund",
                    "index_version": "index-v1"
                  }
                }
                """.formatted(event, tenant, document, version);

        KnowledgeIndexMessage message = KnowledgeIndexMessage.parse(
                json,
                new ObjectMapper()
        );

        assertEquals(event, message.eventId());
        assertEquals(version, message.documentVersionId());
        assertEquals("index-v1", message.indexVersion());
    }

    @Test
    void rejectsMissingOrInvalidUuidAsPermanent() {
        assertThrows(
                KnowledgeIndexPermanentException.class,
                () -> KnowledgeIndexMessage.parse(
                        "{\"event_id\":\"bad\",\"payload\":{}}",
                        new ObjectMapper()
                )
        );
    }
}
