package com.supportforge.persistence.postgres;

import com.supportforge.config.DatabaseProperties;
import com.supportforge.config.KafkaIndexingProperties;
import com.supportforge.indexing.KnowledgeJobClaim;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresKnowledgeIndexJobAdapterTest {

    @Test
    void reportsMissingWithoutCreatingSyntheticWork() throws Exception {
        Fixture fixture = fixture();
        when(fixture.jobResult.next()).thenReturn(false);

        KnowledgeJobClaim claim = fixture.adapter.claim(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "index-v1", "worker", "topic", 0, 0
        );

        assertEquals(KnowledgeJobClaim.Status.MISSING, claim.status());
        verify(fixture.connection).commit();
    }

    @Test
    void duplicateSucceededJobIsAcknowledgedIdempotently()
            throws Exception {
        Fixture fixture = fixture();
        stubJob(fixture, "SUCCEEDED", false);

        KnowledgeJobClaim claim = fixture.adapter.claim(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "index-v1", "worker", "topic", 0, 0
        );

        assertEquals(
                KnowledgeJobClaim.Status.ALREADY_SUCCEEDED,
                claim.status()
        );
    }

    @Test
    void acquiresAvailableJobAndLoadsOrderedChunks() throws Exception {
        Fixture fixture = fixture();
        stubJob(fixture, "PENDING", true);
        when(fixture.chunkResult.next()).thenReturn(true, false);
        when(fixture.chunkResult.getObject("start_offset")).thenReturn(0);
        when(fixture.chunkResult.getObject("end_offset")).thenReturn(2);
        when(fixture.chunkResult.getInt("chunk_index")).thenReturn(0);
        when(fixture.chunkResult.getString("content")).thenReturn("正文");
        when(fixture.chunkResult.getString("content_sha256"))
                .thenReturn("a".repeat(64));
        when(fixture.chunkResult.getInt("token_count")).thenReturn(2);
        when(fixture.statement.executeUpdate()).thenReturn(1);

        KnowledgeJobClaim claim = fixture.adapter.claim(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "index-v1", "worker", "topic", 2, 9
        );

        assertEquals(KnowledgeJobClaim.Status.ACQUIRED, claim.status());
        assertEquals(1, claim.work().chunks().size());
        assertEquals("正文", claim.work().chunks().getFirst().content());
        verify(fixture.connection).commit();
    }

    private void stubJob(
            Fixture fixture,
            String status,
            boolean available
    ) throws Exception {
        when(fixture.jobResult.next()).thenReturn(true);
        when(fixture.jobResult.getObject("id", UUID.class))
                .thenReturn(UUID.randomUUID());
        when(fixture.jobResult.getObject("document_id", UUID.class))
                .thenReturn(UUID.randomUUID());
        when(fixture.jobResult.getString("external_key")).thenReturn("refund");
        when(fixture.jobResult.getString("category")).thenReturn("BILLING");
        when(fixture.jobResult.getString("status")).thenReturn(status);
        when(fixture.jobResult.getInt("attempt_count")).thenReturn(0);
        when(fixture.jobResult.getInt("max_attempts")).thenReturn(10);
        when(fixture.jobResult.getBoolean("available")).thenReturn(available);
    }

    private Fixture fixture() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet jobResult = mock(ResultSet.class);
        ResultSet chunkResult = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(jobResult, chunkResult);
        PostgresKnowledgeIndexJobAdapter adapter =
                new PostgresKnowledgeIndexJobAdapter(
                        dataSource,
                        new DatabaseProperties(
                                "jdbc:postgresql://localhost:5432/supportforge",
                                "supportforge", "", "supportforge",
                                10, 1, Duration.ofSeconds(5), true
                        ),
                        new KafkaIndexingProperties(
                                "localhost:9092", "topic", "topic.dlt",
                                "group", "client", 1, (short) 1,
                                Duration.ofSeconds(1), Duration.ofSeconds(10),
                                Duration.ofSeconds(1), 3,
                                Duration.ofMinutes(1)
                        )
                );
        return new Fixture(
                adapter, connection, statement, jobResult, chunkResult
        );
    }

    private record Fixture(
            PostgresKnowledgeIndexJobAdapter adapter,
            Connection connection,
            PreparedStatement statement,
            ResultSet jobResult,
            ResultSet chunkResult
    ) {
    }
}
