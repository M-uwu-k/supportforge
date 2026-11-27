package com.supportforge.persistence.postgres;

import com.supportforge.config.DatabaseProperties;
import com.supportforge.intent.IntentCategory;
import com.supportforge.intent.IntentLearningSample;
import com.supportforge.persistence.port.IntentLearningPersistencePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;


@Component
@DependsOn("supportForgeFlyway")
@ConditionalOnProperty(
        prefix = "supportforge.persistence",
        name = "intent-learning",
        havingValue = "postgres"
)
public class PostgresIntentLearningAdapter
        implements IntentLearningPersistencePort {

    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final DataSource dataSource;
    private final UUID tenantId;
    private final int maxSamples;
    private final String tableName;

    public PostgresIntentLearningAdapter(
            DataSource dataSource,
            DatabaseProperties databaseProperties,
            @Value("${supportforge.persistence.default-tenant-id:00000000-0000-0000-0000-000000000001}")
            String defaultTenantId,
            @Value("${supportforge.intent.learning.max-samples:1000}")
            int maxSamples
    ) {
        if (maxSamples < 1) {
            throw new IllegalArgumentException(
                    "意图学习样本最大数量必须大于0"
            );
        }
        String schema = databaseProperties.schema();
        if (schema == null
                || !SAFE_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException(
                    "PostgreSQL schema 不是安全标识符：" + schema
            );
        }
        this.dataSource = dataSource;
        this.tenantId = parseTenantId(defaultTenantId);
        this.maxSamples = maxSamples;
        this.tableName = schema + ".intent_learning_samples";
    }

    @Override
    public List<IntentLearningSample> findAll() {
        String sql = """
                SELECT message, correct_intent
                  FROM %s
                 WHERE tenant_id = ?
                   AND enabled = TRUE
                 ORDER BY updated_at, created_at, id
                """.formatted(tableName);

        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            List<IntentLearningSample> samples = new ArrayList<>();
            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {
                statement.setObject(1, tenantId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        samples.add(
                                new IntentLearningSample(
                                        resultSet.getString("message"),
                                        resultSet.getString("correct_intent")
                                )
                        );
                    }
                }
            }
            connection.commit();
            return List.copyOf(samples);
        } catch (SQLException | RuntimeException exception) {
            rollbackQuietly(connection);
            throw new IllegalStateException(
                    "读取PostgreSQL意图学习样本失败",
                    exception
            );
        } finally {
            closeQuietly(connection);
        }
    }

    @Override
    public synchronized boolean upsert(
            String message,
            IntentCategory correctIntent
    ) {
        IntentLearningSample sample =
                new IntentLearningSample(message, correctIntent);
        String normalizedMessage =
                sample.message().strip().toLowerCase(Locale.ROOT);

        String upsertSql = """
                INSERT INTO %s (
                    id, tenant_id, message, normalized_message,
                    correct_intent, source, enabled
                ) VALUES (?, ?, ?, ?, ?, 'HUMAN_CORRECTION', TRUE)
                ON CONFLICT (tenant_id, normalized_message)
                DO UPDATE SET
                    message = EXCLUDED.message,
                    correct_intent = EXCLUDED.correct_intent,
                    enabled = TRUE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE %s.correct_intent IS DISTINCT FROM EXCLUDED.correct_intent
                   OR %s.message IS DISTINCT FROM EXCLUDED.message
                   OR %s.enabled = FALSE
                """.formatted(tableName, tableName, tableName, tableName);

        String trimSql = """
                DELETE FROM %s
                 WHERE tenant_id = ?
                   AND id IN (
                       SELECT id
                         FROM %s
                        WHERE tenant_id = ?
                        ORDER BY updated_at DESC, created_at DESC, id DESC
                        OFFSET ?
                   )
                """.formatted(tableName, tableName);

        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            int changed;
            try (PreparedStatement statement =
                         connection.prepareStatement(upsertSql)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, tenantId());
                statement.setString(3, sample.message());
                statement.setString(4, normalizedMessage);
                statement.setString(5, sample.intent());
                changed = statement.executeUpdate();
            }

            if (changed > 0) {
                try (PreparedStatement statement =
                             connection.prepareStatement(trimSql)) {
                    statement.setObject(1, tenantId());
                    statement.setObject(2, tenantId());
                    statement.setInt(3, maxSamples);
                    statement.executeUpdate();
                }
            }
            connection.commit();
            return changed > 0;
        } catch (SQLException | RuntimeException exception) {
            rollbackQuietly(connection);
            throw new IllegalStateException(
                    "保存PostgreSQL意图学习样本失败",
                    exception
            );
        } finally {
            closeQuietly(connection);
        }
    }

    private static UUID parseTenantId(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "默认租户编号必须是UUID：" + value,
                    exception
            );
        }
    }

    private static void rollbackQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {

        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {

        }
    }


    private UUID tenantId() {
        return com.supportforge.security.TenantContext.current()
                .map(com.supportforge.security.RequestIdentity::tenantId)
                .orElse(tenantId);
    }

    @Override
    public String scopeKey() { return "postgres:" + tenantId(); }
}
