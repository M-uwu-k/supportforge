package com.supportforge.security;

import com.supportforge.config.DatabaseProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "supportforge.features", name = "postgres-persistence", havingValue = "true"
)
public class PostgresResourceAclAdapter implements ResourceAclPort {
    private final DataSource dataSource;
    private final String table;

    public PostgresResourceAclAdapter(DataSource source, DatabaseProperties database) {
        if (!database.schema().matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("数据库schema名称不安全");
        }
        this.dataSource = source;
        this.table = database.schema() + ".resource_acl";
    }

    @Override
    public void grant(
            UUID tenantId, String resourceType, String resourceId,
            String principalId, AclPermission permission, String grantedBy
    ) {
        String sql = """
                INSERT INTO %s (
                    id,tenant_id,resource_type,resource_id,principal_id,permission,granted_by
                ) VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (tenant_id,resource_type,resource_id,principal_id,permission)
                DO UPDATE SET granted_by=EXCLUDED.granted_by
                """.formatted(table);
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, tenantId);
                statement.setString(3, resourceType);
                statement.setString(4, resourceId);
                statement.setString(5, principalId);
                statement.setString(6, permission.name());
                statement.setString(7, grantedBy);
                statement.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("保存资源ACL失败", exception);
        }
    }

    @Override
    public boolean allowed(
            UUID tenantId, String resourceType, String resourceId,
            String principalId, AclPermission permission
    ) {
        String sql = """
                SELECT 1 FROM %s
                 WHERE tenant_id=? AND resource_type=? AND resource_id=?
                   AND principal_id=? AND permission IN (?, 'ADMIN') LIMIT 1
                """.formatted(table);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setString(2, resourceType);
            statement.setString(3, resourceId);
            statement.setString(4, principalId);
            statement.setString(5, permission.name());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询资源ACL失败", exception);
        }
    }
}
