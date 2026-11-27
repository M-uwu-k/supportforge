package com.supportforge.persistence.postgres;

import com.supportforge.config.DatabaseProperties;
import com.supportforge.trace.RetrievalTrace;
import com.supportforge.trace.RetrievalTraceEvidence;
import com.supportforge.trace.RetrievalTracePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL retrieval_traces Adapter；JSONB保存候选和证据快照。 */
@Component
@ConditionalOnProperty(
        prefix = "supportforge.features", name = "postgres-persistence",
        havingValue = "true"
)
public class PostgresRetrievalTraceAdapter implements RetrievalTracePort {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final String table;

    public PostgresRetrievalTraceAdapter(
            DataSource dataSource,
            ObjectMapper objectMapper,
            DatabaseProperties database
    ) {
        if (!database.schema().matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("数据库schema名称不安全");
        }
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.table = database.schema() + ".retrieval_traces";
    }

    @Override
    public void save(RetrievalTrace trace) {
        String sql = """
                INSERT INTO %s (
                    id, tenant_id, original_query, rewritten_queries,
                    filters, recalled_chunks, selected_evidence,
                    embedding_model, reranker_model, retrieval_latency_ms,
                    created_at
                ) VALUES (?, ?, ?, '[]'::jsonb, ?::jsonb, ?::jsonb,
                          ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """.formatted(table);
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, trace.id());
                statement.setObject(2, trace.tenantId());
                statement.setString(3, trace.originalQuery());
                statement.setString(4, objectMapper.writeValueAsString(trace.filters()));
                statement.setString(5, objectMapper.writeValueAsString(trace.recalledChunks()));
                statement.setString(6, objectMapper.writeValueAsString(trace.selectedEvidence()));
                statement.setString(7, trace.embeddingModel());
                statement.setString(8, trace.rerankerModel());
                statement.setLong(9, trace.retrievalLatencyMs());
                statement.setTimestamp(10, Timestamp.from(trace.createdAt()));
                statement.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("保存检索轨迹失败", exception);
        }
    }

    @Override
    public Optional<RetrievalTrace> find(UUID tenantId, UUID traceId) {
        String sql = """
                SELECT id, tenant_id, original_query, filters::text,
                       recalled_chunks::text, selected_evidence::text,
                       embedding_model, reranker_model,
                       retrieval_latency_ms, created_at
                  FROM %s
                 WHERE tenant_id = ? AND id = ?
                """.formatted(table);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, traceId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(decode(result));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取检索轨迹失败", exception);
        }
    }

    private RetrievalTrace decode(ResultSet result) throws SQLException {
        try {
            Map<String, Object> filters = objectMapper.readValue(
                    result.getString("filters"), Map.class
            );
            RetrievalTraceEvidence[] recalled = objectMapper.readValue(
                    result.getString("recalled_chunks"),
                    RetrievalTraceEvidence[].class
            );
            RetrievalTraceEvidence[] selected = objectMapper.readValue(
                    result.getString("selected_evidence"),
                    RetrievalTraceEvidence[].class
            );
            Timestamp timestamp = result.getTimestamp("created_at");
            Instant createdAt = timestamp == null ? Instant.now() : timestamp.toInstant();
            return new RetrievalTrace(
                    result.getObject("id", UUID.class),
                    result.getObject("tenant_id", UUID.class),
                    result.getString("original_query"), filters,
                    Arrays.asList(recalled), Arrays.asList(selected),
                    result.getString("embedding_model"),
                    result.getString("reranker_model"),
                    result.getLong("retrieval_latency_ms"), createdAt
            );
        } catch (RuntimeException exception) {
            throw new IllegalStateException("检索轨迹JSON损坏", exception);
        }
    }
}
