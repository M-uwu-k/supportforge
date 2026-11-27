package com.supportforge.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMigrationContractTest {

    @Test
    void scopesExternalConversationIdByCustomer() throws Exception {
        String sql = Files.readString(
                Path.of("src/main/resources/db/migration/V9__scope_conversations_by_customer.sql"),
                StandardCharsets.UTF_8
        );

        assertTrue(sql.contains("DROP CONSTRAINT uq_conversations_external"));
        assertTrue(sql.contains("tenant_id, customer_id, external_conversation_id"));
        assertFalse(sql.contains("DROP TABLE"));
    }
}
