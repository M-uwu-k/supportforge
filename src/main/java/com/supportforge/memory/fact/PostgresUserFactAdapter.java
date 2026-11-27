package com.supportforge.memory.fact;

import com.supportforge.config.DatabaseProperties;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "supportforge.features", name = "postgres-persistence",
        havingValue = "true"
)
public class PostgresUserFactAdapter implements UserFactPort {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final String schema;

    public PostgresUserFactAdapter(
            DataSource dataSource, ObjectMapper objectMapper, DatabaseProperties database
    ) {
        if (!database.schema().matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("数据库schema名称不安全");
        }
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.schema = database.schema();
    }

    @Override
    public UserFact replace(
            UUID tenantId, String userId, String key, String value,
            UserFactSource source, String sourceReference,
            double confidence, Instant expiresAt
    ) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                UUID customerId = upsertCustomer(connection, tenantId, userId);
                UUID previousId = lockActive(connection, tenantId, customerId, key);
                Instant now = Instant.now();
                if (previousId != null) {
                    supersede(connection, tenantId, previousId, now);
                }
                UserFact fact = new UserFact(
                        UUID.randomUUID(), tenantId, userId, key, value, source,
                        sourceReference, confidence, now, expiresAt, previousId
                );
                insert(connection, customerId, fact);
                connection.commit();
                return fact;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("保存用户事实失败", exception);
        }
    }

    @Override
    public List<UserFact> findActive(UUID tenantId, String userId, Instant now) {
        String sql = """
                SELECT f.id, f.fact_key, f.fact_value::text, f.source_type,
                       f.source_reference, f.confidence, f.valid_from, f.valid_to,
                       f.supersedes_fact_id
                  FROM %s.user_facts f
                  JOIN %s.customer_identities c
                    ON c.tenant_id=f.tenant_id AND c.id=f.customer_id
                 WHERE f.tenant_id=? AND c.external_user_id=?
                   AND f.status='ACTIVE' AND (f.valid_to IS NULL OR f.valid_to>?)
                 ORDER BY f.fact_key
                """.formatted(schema, schema);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setString(2, userId.strip());
            statement.setTimestamp(3, Timestamp.from(now));
            try (ResultSet result = statement.executeQuery()) {
                List<UserFact> facts = new ArrayList<>();
                while (result.next()) {
                    facts.add(decode(result, tenantId, userId));
                }
                return List.copyOf(facts);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取用户事实失败", exception);
        }
    }

    @Override
    public boolean revoke(UUID tenantId, String userId, String key, Instant now) {
        String sql = """
                UPDATE %s.user_facts f
                   SET status='REVOKED', valid_to=?, updated_at=CURRENT_TIMESTAMP
                  FROM %s.customer_identities c
                 WHERE f.tenant_id=? AND f.customer_id=c.id
                   AND c.tenant_id=f.tenant_id AND c.external_user_id=?
                   AND f.fact_key=? AND f.status='ACTIVE'
                """.formatted(schema, schema);
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setTimestamp(1, Timestamp.from(now));
                statement.setObject(2, tenantId);
                statement.setString(3, userId.strip());
                statement.setString(4, key.strip());
                boolean changed = statement.executeUpdate() > 0;
                connection.commit();
                return changed;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("撤销用户事实失败", exception);
        }
    }

    private UUID upsertCustomer(
            Connection connection, UUID tenantId, String userId
    ) throws SQLException {
        String sql = """
                INSERT INTO %s.customer_identities(id,tenant_id,external_user_id)
                VALUES (?,?,?)
                ON CONFLICT (tenant_id,external_user_id)
                DO UPDATE SET updated_at=CURRENT_TIMESTAMP
                RETURNING id
                """.formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, tenantId);
            statement.setString(3, userId.strip());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private UUID lockActive(
            Connection connection, UUID tenantId, UUID customerId, String key
    ) throws SQLException {
        String sql = "SELECT id FROM %s.user_facts WHERE tenant_id=? AND customer_id=? AND fact_key=? AND status='ACTIVE' FOR UPDATE"
                .formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, customerId);
            statement.setString(3, key.strip());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getObject(1, UUID.class) : null;
            }
        }
    }

    private void supersede(
            Connection connection, UUID tenantId, UUID id, Instant now
    ) throws SQLException {
        String sql = "UPDATE %s.user_facts SET status='SUPERSEDED',valid_to=?,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?"
                .formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setObject(2, tenantId);
            statement.setObject(3, id);
            statement.executeUpdate();
        }
    }

    private void insert(Connection connection, UUID customerId, UserFact fact) throws SQLException {
        String sql = """
                INSERT INTO %s.user_facts (
                    id,tenant_id,customer_id,fact_key,fact_value,source_type,
                    source_reference,confidence,valid_from,valid_to,status,supersedes_fact_id
                ) VALUES (?,?,?,?,?::jsonb,?,?,?,?,?,'ACTIVE',?)
                """.formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, fact.id());
            statement.setObject(2, fact.tenantId());
            statement.setObject(3, customerId);
            statement.setString(4, fact.key());
            statement.setString(5, objectMapper.writeValueAsString(fact.value()));
            statement.setString(6, fact.source().name());
            statement.setString(7, fact.sourceReference());
            statement.setDouble(8, fact.confidence());
            statement.setTimestamp(9, Timestamp.from(fact.validFrom()));
            statement.setTimestamp(10, fact.expiresAt() == null ? null : Timestamp.from(fact.expiresAt()));
            statement.setObject(11, fact.supersedesFactId());
            statement.executeUpdate();
        }
    }

    private UserFact decode(ResultSet result, UUID tenantId, String userId) throws SQLException {
        String value = objectMapper.readValue(result.getString("fact_value"), String.class);
        Timestamp expires = result.getTimestamp("valid_to");
        return new UserFact(
                result.getObject("id", UUID.class), tenantId, userId,
                result.getString("fact_key"), value,
                UserFactSource.valueOf(result.getString("source_type")),
                result.getString("source_reference"), result.getDouble("confidence"),
                result.getTimestamp("valid_from").toInstant(),
                expires == null ? null : expires.toInstant(),
                result.getObject("supersedes_fact_id", UUID.class)
        );
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
