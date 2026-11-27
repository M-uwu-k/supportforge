package com.supportforge.persistence.postgres;

import com.supportforge.config.DatabaseProperties;
import com.supportforge.evaluation.EvalReport;
import com.supportforge.evaluation.EvalResult;
import com.supportforge.persistence.port.EvaluationReportPersistencePort;
import com.supportforge.security.TenantContextProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** PostgreSQL评测报告Adapter：按当前租户读写最近的完整评测历史。 */
@Component
@DependsOn("supportForgeFlyway")
@ConditionalOnProperty(
        prefix = "supportforge.persistence", name = "evaluation", havingValue = "postgres"
)
public class PostgresEvaluationReportAdapter implements EvaluationReportPersistencePort {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final TenantContextProvider tenants;
    private final String schema;
    private final int maxHistory;

    public PostgresEvaluationReportAdapter(
            DataSource dataSource,
            ObjectMapper objectMapper,
            TenantContextProvider tenants,
            DatabaseProperties database,
            @Value("${supportforge.evaluation.max-history:50}") int maxHistory
    ) {
        if (!database.schema().matches("[A-Za-z_][A-Za-z0-9_]*") || maxHistory < 1) {
            throw new IllegalArgumentException("PostgreSQL评测配置不合法");
        }
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.tenants = tenants;
        this.schema = database.schema();
        this.maxHistory = maxHistory;
    }

    @Override
    public String scopeKey() {
        return tenants.tenantId().toString();
    }

    @Override
    public List<EvalReport> load() {
        String sql = """
                SELECT id, sample_count, metrics::text, item_results::text,
                       metadata::text, completed_at, created_at
                  FROM %s.evaluation_reports
                 WHERE tenant_id=? AND evaluation_type='END_TO_END' AND status='COMPLETED'
                 ORDER BY created_at DESC, id DESC LIMIT ?
                """.formatted(schema);
        List<EvalReport> newestFirst = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenants.tenantId());
            statement.setInt(2, maxHistory);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    newestFirst.add(map(result));
                }
            }
            connection.commit();
        } catch (Exception exception) {
            throw new IllegalStateException("读取PostgreSQL评测报告失败", exception);
        }
        List<EvalReport> chronological = new ArrayList<>(newestFirst.size());
        for (int index = newestFirst.size() - 1; index >= 0; index--) {
            chronological.add(newestFirst.get(index));
        }
        return List.copyOf(chronological);
    }

    @Override
    public synchronized void save(List<EvalReport> reports) {
        UUID tenantId = tenants.tenantId();
        List<EvalReport> normalized = keepLatest(reports);
        String insert = """
                INSERT INTO %s.evaluation_reports (
                    id, tenant_id, evaluation_type, dataset_version, model_name,
                    status, sample_count, metrics, item_results, metadata,
                    started_at, completed_at, created_at
                ) VALUES (?, ?, 'END_TO_END', 'runtime', 'supportforge', 'COMPLETED',
                          ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    sample_count=EXCLUDED.sample_count,
                    metrics=EXCLUDED.metrics,
                    item_results=EXCLUDED.item_results,
                    metadata=EXCLUDED.metadata,
                    completed_at=EXCLUDED.completed_at
                """.formatted(schema);
        try (Connection connection = dataSource.getConnection()) {
            try {
                for (EvalReport report : normalized) {
                    Instant timestamp = parseTimestamp(report.timestamp());
                    UUID id = stableId(tenantId, report);
                    try (PreparedStatement statement = connection.prepareStatement(insert)) {
                        statement.setObject(1, id);
                        statement.setObject(2, tenantId);
                        statement.setInt(3, report.total());
                        statement.setString(4, objectMapper.writeValueAsString(Map.of(
                                "total", report.total(),
                                "passed", report.passed(),
                                "passRate", report.passRate(),
                                "avgScores", report.avgScores()
                        )));
                        statement.setString(5, objectMapper.writeValueAsString(report.results()));
                        statement.setString(6, objectMapper.writeValueAsString(Map.of(
                                "timestamp", report.timestamp(),
                                "regressions", report.regressions(),
                                "recommendations", report.recommendations()
                        )));
                        statement.setTimestamp(7, Timestamp.from(timestamp));
                        statement.setTimestamp(8, Timestamp.from(timestamp));
                        statement.setTimestamp(9, Timestamp.from(timestamp));
                        statement.executeUpdate();
                    }
                }
                prune(connection, tenantId);
                connection.commit();
            } catch (Exception exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("保存PostgreSQL评测报告失败", exception);
        }
    }

    @Override
    public List<EvalReport> keepLatest(List<EvalReport> reports) {
        if (reports == null || reports.isEmpty()) {
            return List.of();
        }
        List<EvalReport> snapshot = new ArrayList<>(reports);
        return List.copyOf(snapshot.subList(
                Math.max(0, snapshot.size() - maxHistory), snapshot.size()
        ));
    }

    private EvalReport map(ResultSet result) throws Exception {
        Map<String, Object> metrics = objectMapper.readValue(result.getString("metrics"), OBJECT_MAP);
        Map<String, Object> metadata = objectMapper.readValue(result.getString("metadata"), OBJECT_MAP);
        EvalResult[] items = objectMapper.readValue(result.getString("item_results"), EvalResult[].class);
        int total = intValue(metrics.getOrDefault("total", result.getInt("sample_count")));
        int passed = intValue(metrics.getOrDefault("passed", 0));
        double passRate = doubleValue(metrics.getOrDefault("passRate", 0.0));
        Map<String, Double> avgScores = doubleMap(metrics.get("avgScores"));
        String timestamp = String.valueOf(metadata.getOrDefault(
                "timestamp", result.getTimestamp("completed_at").toInstant().toString()
        ));
        return new EvalReport(
                timestamp, total, passed, passRate, avgScores,
                stringList(metadata.get("regressions")),
                stringList(metadata.get("recommendations")),
                items == null ? List.of() : List.of(items)
        );
    }

    private void prune(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM %s.evaluation_reports
                 WHERE tenant_id=? AND evaluation_type='END_TO_END' AND id IN (
                       SELECT id FROM %s.evaluation_reports
                        WHERE tenant_id=? AND evaluation_type='END_TO_END'
                        ORDER BY created_at DESC, id DESC OFFSET ?)
                """.formatted(schema, schema))) {
            statement.setObject(1, tenantId);
            statement.setObject(2, tenantId);
            statement.setInt(3, maxHistory);
            statement.executeUpdate();
        }
    }

    private static UUID stableId(UUID tenantId, EvalReport report) {
        String value = tenantId + "/evaluation/" + report.timestamp();
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Instant parseTimestamp(String timestamp) {
        try {
            return Instant.parse(timestamp);
        } catch (RuntimeException exception) {
            return Instant.now();
        }
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value));
    }

    private static Map<String, Double> doubleMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Double> values = new LinkedHashMap<>();
        source.forEach((key, item) -> values.put(String.valueOf(key), doubleValue(item)));
        return Map.copyOf(values);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> source)) {
            return List.of();
        }
        return source.stream().map(String::valueOf).toList();
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException failure) {
            original.addSuppressed(failure);
        }
    }
}
