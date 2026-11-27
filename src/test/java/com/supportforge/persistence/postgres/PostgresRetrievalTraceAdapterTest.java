package com.supportforge.persistence.postgres;

import com.supportforge.config.DatabaseProperties;
import com.supportforge.trace.RetrievalTrace;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresRetrievalTraceAdapterTest {

    @Test
    void insertsJsonbTraceIntoConfiguredSchema() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        RetrievalTrace trace = new RetrievalTrace(
                UUID.randomUUID(), UUID.randomUUID(), "query",
                Map.of("allowed", true), List.of(), List.of(),
                "embedding", "reranker", 20, Instant.now()
        );

        adapter(dataSource).save(trace);

        verify(connection).prepareStatement(argThat(sql ->
                sql.contains("supportforge.retrieval_traces")
                        && sql.contains("ON CONFLICT (id) DO NOTHING")
                        && sql.contains("?::jsonb")));
        verify(statement).executeUpdate();
        verify(connection).commit();
    }

    @Test
    void findReturnsEmptyForUnknownTenantScopedTrace() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertTrue(adapter(dataSource).find(
                UUID.randomUUID(), UUID.randomUUID()
        ).isEmpty());
    }

    private PostgresRetrievalTraceAdapter adapter(DataSource source) {
        return new PostgresRetrievalTraceAdapter(
                source, new ObjectMapper(),
                new DatabaseProperties(
                        "jdbc:postgresql://localhost:5432/supportforge",
                        "user", "", "supportforge", 10, 1,
                        Duration.ofSeconds(1), true
                )
        );
    }
}
