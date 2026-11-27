package com.supportforge.persistence.postgres;

import com.supportforge.config.DatabaseProperties;
import com.supportforge.config.OutboxProperties;
import com.supportforge.outbox.OutboxEvent;
import com.supportforge.outbox.OutboxRepositoryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** PostgreSQL Transactional Outbox Adapter，使用SKIP LOCKED和有主租约领取。 */
@Component
@DependsOn("supportForgeFlyway")
@ConditionalOnProperty(
        prefix = "supportforge.features",
        name = "postgres-persistence",
        havingValue = "true"
)
public class PostgresOutboxAdapter implements OutboxRepositoryPort {

    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;
    private final String table;

    public PostgresOutboxAdapter(
            DataSource dataSource,
            ObjectMapper objectMapper,
            DatabaseProperties databaseProperties,
            OutboxProperties properties
    ) {
        String schema = databaseProperties.schema();
        if (schema == null || !SAFE_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException(
                    "PostgreSQL schema 不是安全标识符：" + schema
            );
        }
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.table = schema + ".outbox_events";
    }

    @Override
    public List<OutboxEvent> claim(String owner) {
        requireOwner(owner);
        String claimSql = """
                WITH candidates AS (
                    SELECT id
                      FROM %s
                     WHERE attempt_count < ?
                       AND (
                           (status IN ('PENDING', 'FAILED')
                               AND available_at <= CURRENT_TIMESTAMP)
                           OR (status = 'PUBLISHING'
                               AND lease_until < CURRENT_TIMESTAMP)
                       )
                     ORDER BY available_at, created_at, id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                )
                UPDATE %s event
                   SET status = 'PUBLISHING',
                       attempt_count = event.attempt_count + 1,
                       lease_owner = ?,
                       lease_until = CURRENT_TIMESTAMP
                           + (? * INTERVAL '1 millisecond'),
                       updated_at = CURRENT_TIMESTAMP
                  FROM candidates
                 WHERE event.id = candidates.id
                RETURNING event.id, event.tenant_id,
                          event.aggregate_type, event.aggregate_id,
                          event.aggregate_version,
                          event.event_type, event.event_version,
                          event.topic, event.partition_key,
                          event.idempotency_key,
                          event.payload::text AS payload_json,
                          event.headers::text AS headers_json,
                          event.attempt_count, event.lease_until,
                          event.created_at
                """.formatted(table, table);

        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            markExhaustedAsDead(connection);
            List<OutboxEvent> events = new ArrayList<>();
            try (PreparedStatement statement =
                         connection.prepareStatement(claimSql)) {
                statement.setInt(1, properties.maximumAttempts());
                statement.setInt(2, properties.claimBatchSize());
                statement.setString(3, owner);
                statement.setLong(4, properties.leaseDuration().toMillis());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        events.add(readEvent(resultSet));
                    }
                }
            }
            connection.commit();
            return List.copyOf(events);
        } catch (SQLException | RuntimeException exception) {
            rollbackQuietly(connection);
            throw new IllegalStateException("领取Outbox事件失败", exception);
        } finally {
            closeQuietly(connection);
        }
    }

    @Override
    public void markPublished(UUID eventId, String owner) {
        requireIdentity(eventId, owner);
        String sql = """
                UPDATE %s
                   SET status = 'PUBLISHED',
                       published_at = CURRENT_TIMESTAMP,
                       lease_owner = NULL,
                       lease_until = NULL,
                       last_error = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                   AND status = 'PUBLISHING'
                   AND lease_owner = ?
                   AND lease_until >= CURRENT_TIMESTAMP
                """.formatted(table);
        executeOwnedUpdate(sql, eventId, owner, "确认Outbox发布");
    }

    @Override
    public void markFailed(
            UUID eventId,
            String owner,
            String errorMessage
    ) {
        requireIdentity(eventId, owner);
        if (errorMessage == null || errorMessage.isBlank()) {
            throw new IllegalArgumentException("Outbox失败原因不能为空");
        }
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            int attemptCount = lockOwnedAttempt(
                    connection,
                    eventId,
                    owner
            );
            boolean dead = attemptCount >= properties.maximumAttempts();
            Duration delay = properties.retryDelayForAttempt(attemptCount);
            String sql = """
                    UPDATE %s
                       SET status = ?,
                           available_at = ?,
                           dead_at = CASE WHEN ? THEN CURRENT_TIMESTAMP
                                          ELSE NULL END,
                           lease_owner = NULL,
                           lease_until = NULL,
                           last_error = ?,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE id = ?
                       AND status = 'PUBLISHING'
                       AND lease_owner = ?
                    """.formatted(table);
            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {
                statement.setString(1, dead ? "DEAD" : "FAILED");
                statement.setTimestamp(
                        2,
                        Timestamp.from(Instant.now().plus(delay))
                );
                statement.setBoolean(3, dead);
                statement.setString(4, abbreviate(errorMessage, 4_000));
                statement.setObject(5, eventId);
                statement.setString(6, owner);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Outbox租约已丢失");
                }
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            rollbackQuietly(connection);
            throw new IllegalStateException("记录Outbox发布失败状态失败", exception);
        } finally {
            closeQuietly(connection);
        }
    }

    private void markExhaustedAsDead(Connection connection)
            throws SQLException {
        String sql = """
                UPDATE %s
                   SET status = 'DEAD',
                       dead_at = CURRENT_TIMESTAMP,
                       lease_owner = NULL,
                       lease_until = NULL,
                       last_error = COALESCE(
                           last_error,
                           'Outbox lease expired after maximum attempts'
                       ),
                       updated_at = CURRENT_TIMESTAMP
                 WHERE attempt_count >= ?
                   AND status IN ('PENDING', 'FAILED', 'PUBLISHING')
                   AND (status <> 'PUBLISHING'
                        OR lease_until < CURRENT_TIMESTAMP)
                """.formatted(table);
        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setInt(1, properties.maximumAttempts());
            statement.executeUpdate();
        }
    }

    private int lockOwnedAttempt(
            Connection connection,
            UUID eventId,
            String owner
    ) throws SQLException {
        String sql = """
                SELECT attempt_count
                  FROM %s
                 WHERE id = ?
                   AND status = 'PUBLISHING'
                   AND lease_owner = ?
                   AND lease_until >= CURRENT_TIMESTAMP
                 FOR UPDATE
                """.formatted(table);
        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setObject(1, eventId);
            statement.setString(2, owner);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Outbox租约不存在、已过期或不属于当前owner");
                }
                return resultSet.getInt("attempt_count");
            }
        }
    }

    private void executeOwnedUpdate(
            String sql,
            UUID eventId,
            String owner,
            String action
    ) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {
                statement.setObject(1, eventId);
                statement.setString(2, owner);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Outbox租约已过期或不属于当前owner");
                }
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            rollbackQuietly(connection);
            throw new IllegalStateException(action + "失败", exception);
        } finally {
            closeQuietly(connection);
        }
    }

    @SuppressWarnings("unchecked")
    private OutboxEvent readEvent(ResultSet resultSet)
            throws SQLException {
        Map<String, Object> payload = objectMapper.readValue(
                resultSet.getString("payload_json"),
                Map.class
        );
        Map<String, Object> headers = objectMapper.readValue(
                resultSet.getString("headers_json"),
                Map.class
        );
        return new OutboxEvent(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getString("aggregate_type"),
                resultSet.getObject("aggregate_id", UUID.class),
                resultSet.getLong("aggregate_version"),
                resultSet.getString("event_type"),
                resultSet.getInt("event_version"),
                resultSet.getString("topic"),
                resultSet.getString("partition_key"),
                resultSet.getString("idempotency_key"),
                payload,
                headers,
                resultSet.getInt("attempt_count"),
                resultSet.getTimestamp("lease_until").toInstant(),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    private static void requireIdentity(UUID eventId, String owner) {
        if (eventId == null) {
            throw new IllegalArgumentException("Outbox事件ID不能为空");
        }
        requireOwner(owner);
    }

    private static void requireOwner(String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 200) {
            throw new IllegalArgumentException("Outbox owner不能为空且最多200字符");
        }
    }

    private static String abbreviate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
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
}
