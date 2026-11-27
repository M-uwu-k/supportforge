package com.supportforge.persistence.postgres;

import com.supportforge.config.DatabaseProperties;
import com.supportforge.config.OutboxProperties;
import com.supportforge.outbox.OutboxEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresOutboxAdapterTest {

    @Test
    void claimsWithSkipLockedAndReturnsStronglyTypedEvent()
            throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        UUID eventId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        when(resultSet.getObject("id", UUID.class)).thenReturn(eventId);
        when(resultSet.getObject("tenant_id", UUID.class)).thenReturn(tenantId);
        when(resultSet.getString("aggregate_type")).thenReturn("Version");
        when(resultSet.getObject("aggregate_id", UUID.class)).thenReturn(aggregateId);
        when(resultSet.getLong("aggregate_version")).thenReturn(1L);
        when(resultSet.getString("event_type")).thenReturn("chunks.ready.v1");
        when(resultSet.getInt("event_version")).thenReturn(1);
        when(resultSet.getString("topic")).thenReturn("topic");
        when(resultSet.getString("partition_key")).thenReturn("partition");
        when(resultSet.getString("idempotency_key")).thenReturn("idem");
        when(resultSet.getString("payload_json")).thenReturn("{\"count\":2}");
        when(resultSet.getString("headers_json")).thenReturn("{\"v\":1}");
        when(resultSet.getInt("attempt_count")).thenReturn(1);
        Instant now = Instant.now();
        when(resultSet.getTimestamp("lease_until"))
                .thenReturn(Timestamp.from(now.plusSeconds(30)));
        when(resultSet.getTimestamp("created_at"))
                .thenReturn(Timestamp.from(now));

        List<OutboxEvent> events = adapter(dataSource).claim("publisher-1");

        assertEquals(1, events.size());
        assertEquals(eventId, events.getFirst().id());
        assertEquals(2, ((Number) events.getFirst().payload().get("count")).intValue());
        verify(connection, atLeastOnce()).prepareStatement(argThat(sql ->
                sql.contains("FOR UPDATE SKIP LOCKED")));
        verify(connection).commit();
    }

    @Test
    void publishedTransitionRequiresOwnedLiveLease() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        adapter(dataSource).markPublished(UUID.randomUUID(), "publisher-1");

        verify(connection).prepareStatement(argThat(sql ->
                sql.contains("lease_owner = ?")
                        && sql.contains("lease_until >= CURRENT_TIMESTAMP")));
        verify(connection).commit();
    }

    @Test
    void failureCannotBeRecordedAfterLeaseIsLost() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertThrows(
                IllegalStateException.class,
                () -> adapter(dataSource).markFailed(
                        UUID.randomUUID(), "publisher-1", "broker down"
                )
        );
        verify(connection).rollback();
    }

    private PostgresOutboxAdapter adapter(DataSource dataSource) {
        return new PostgresOutboxAdapter(
                dataSource,
                new ObjectMapper(),
                new DatabaseProperties(
                        "jdbc:postgresql://localhost:5432/supportforge",
                        "supportforge", "", "supportforge",
                        10, 1, Duration.ofSeconds(5), true
                ),
                new OutboxProperties(
                        10, Duration.ofSeconds(30), 3,
                        Duration.ofSeconds(1), Duration.ofSeconds(10)
                )
        );
    }
}
