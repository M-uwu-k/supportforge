package com.supportforge.grounding;

import com.supportforge.config.DatabaseProperties;
import com.supportforge.retrieval.HybridSearchHit;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PostgresEvidencePolicyTest {
    private final DataSource source = mock(DataSource.class);
    private final DatabaseProperties database = new DatabaseProperties("", "", "", "supportforge",
            5, 1, Duration.ofSeconds(5), false);

    @Test
    void batchValidationChecksDocumentVersionAndContentRatherThanOnlyPointId() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        var array = mock(Array.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(connection.createArrayOf(eq("uuid"), any(Object[].class))).thenReturn(array);
        when(statement.executeQuery()).thenReturn(rows);
        var good = hit();
        var wrongDocument = hit();
        var wrongVersion = hit();
        var wrongContent = hit();
        when(rows.next()).thenReturn(true, true, true, true, false);
        when(rows.getObject("qdrant_point_id", UUID.class)).thenReturn(good.pointId(),
                wrongDocument.pointId(), wrongVersion.pointId(), wrongContent.pointId());
        when(rows.getObject("document_id", UUID.class)).thenReturn(good.documentId(),
                UUID.randomUUID(), wrongVersion.documentId(), wrongContent.documentId());
        when(rows.getObject("version_id", UUID.class)).thenReturn(good.documentVersionId(),
                wrongDocument.documentVersionId(), UUID.randomUUID(), wrongContent.documentVersionId());
        when(rows.getString("content_sha256")).thenReturn(good.contentSha256(),
                wrongDocument.contentSha256(), wrongVersion.contentSha256(), "b".repeat(64));
        UUID tenant = UUID.randomUUID();

        assertEquals(Set.of(good.pointId()), new PostgresEvidencePolicy(source, database).currentPointIds(
                tenant, List.of(good, wrongDocument, wrongVersion, wrongContent)));
        verify(statement).setObject(1, tenant);
        verify(connection, times(1)).prepareStatement(anyString());
        verify(connection).commit();
        verify(array).free();
    }

    @Test
    void emptyCandidatesDoNotBorrowDatabaseConnection() {
        assertEquals(Set.of(), new PostgresEvidencePolicy(source, database)
                .currentPointIds(UUID.randomUUID(), List.of()));
        verifyNoInteractions(source);
    }

    private HybridSearchHit hit() {
        return new HybridSearchHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "document", 0, "content", "a".repeat(64), "technical", "index-v1", 0.03);
    }
}
