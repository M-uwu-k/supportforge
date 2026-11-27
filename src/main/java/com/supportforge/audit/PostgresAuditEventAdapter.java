package com.supportforge.audit;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "supportforge.features", name = "postgres-persistence", havingValue = "true"
)
public class PostgresAuditEventAdapter implements AuditEventPort {
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final String table;

    public PostgresAuditEventAdapter(
            DataSource dataSource, ObjectMapper objectMapper, DatabaseProperties database
    ) {
        if (!database.schema().matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("数据库schema名称不安全");
        }
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.table = database.schema() + ".audit_events";
    }

    @Override
    public void append(AuditEventRecord event) {
        String sql = """
                INSERT INTO %s (
                    id,tenant_id,actor_type,actor_id,action,resource_type,
                    resource_id,request_id,outcome,details,occurred_at
                ) VALUES (?,?,'API',?,?,?,?,?,?,?::jsonb,?)
                """.formatted(table);
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, event.id());
                statement.setObject(2, event.tenantId());
                statement.setString(3, event.actorId());
                statement.setString(4, event.action());
                statement.setString(5, event.resourceType());
                statement.setString(6, event.resourceId());
                statement.setString(7, event.requestId());
                statement.setString(8, event.outcome());
                statement.setString(9, objectMapper.writeValueAsString(event.details()));
                statement.setTimestamp(10, Timestamp.from(event.occurredAt()));
                statement.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("写入审计事件失败", exception);
        }
    }

    @Override
    public List<AuditEventRecord> recent(UUID tenantId, int limit) {
        String sql = "SELECT * FROM %s WHERE tenant_id=? ORDER BY occurred_at DESC LIMIT ?".formatted(table);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<AuditEventRecord> events = new ArrayList<>();
                while (result.next()) {
                    events.add(new AuditEventRecord(
                            result.getObject("id", UUID.class), tenantId,
                            result.getString("actor_id"), result.getString("action"),
                            result.getString("resource_type"), result.getString("resource_id"),
                            result.getString("request_id"), result.getString("outcome"),
                            objectMapper.readValue(result.getString("details"), Map.class),
                            result.getTimestamp("occurred_at").toInstant()
                    ));
                }
                return events;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取审计事件失败", exception);
        }
    }
}
