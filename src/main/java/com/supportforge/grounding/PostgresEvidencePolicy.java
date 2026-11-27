package com.supportforge.grounding;

import com.supportforge.config.DatabaseProperties;
import com.supportforge.reranker.RerankedSearchHit;
import com.supportforge.retrieval.HybridSearchHit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Qdrant是派生索引。以数据库确认分块仍属于当前租户、当前有效文档版本，拒绝删除/旧版本残留向量。 */
@Service
@ConditionalOnProperty(prefix = "supportforge.features", name = "postgres-persistence", havingValue = "true")
public class PostgresEvidencePolicy {
    private final DataSource dataSource;
    private final String sql;
    private final String candidatesSql;

    public PostgresEvidencePolicy(DataSource dataSource, DatabaseProperties properties) {
        this.dataSource = dataSource;
        String schema = properties.schema();
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("schema名称不安全");
        this.sql = """
                SELECT EXISTS (
                  SELECT 1 FROM %1$s.knowledge_documents d
                  JOIN %1$s.knowledge_document_versions v
                    ON v.tenant_id=d.tenant_id AND v.document_id=d.id AND v.version_no=d.active_version_no
                  JOIN %1$s.knowledge_chunks c
                    ON c.tenant_id=d.tenant_id AND c.document_version_id=v.id
                  WHERE d.tenant_id=? AND d.id=? AND v.id=? AND c.qdrant_point_id=?
                    AND c.content_sha256=? AND d.status='ACTIVE' AND c.index_status='INDEXED'
                )
                """.formatted(schema);
        this.candidatesSql = """
                SELECT c.qdrant_point_id, d.id AS document_id, v.id AS version_id, c.content_sha256
                  FROM %1$s.knowledge_documents d
                  JOIN %1$s.knowledge_document_versions v
                    ON v.tenant_id=d.tenant_id AND v.document_id=d.id AND v.version_no=d.active_version_no
                  JOIN %1$s.knowledge_chunks c
                    ON c.tenant_id=d.tenant_id AND c.document_version_id=v.id
                 WHERE d.tenant_id=? AND d.status='ACTIVE' AND c.index_status='INDEXED'
                   AND c.qdrant_point_id = ANY (?)
                """.formatted(schema);
    }

    /**
     * 搜索和聊天共用的批量准入：在精排和Top-K截断之前排除归档文档、旧版本及未完成索引的分块。
     * 一批候选只查一次数据库；同时核对版本和正文指纹，不因Point编号相同就信任派生索引正文。
     */
    public Set<UUID> currentPointIds(UUID tenantId, List<HybridSearchHit> hits) {
        if (hits.isEmpty()) return Set.of();
        Map<UUID, HybridSearchHit> expected = new LinkedHashMap<>();
        hits.forEach(hit -> expected.putIfAbsent(hit.pointId(), hit));
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(candidatesSql)) {
            var ids = connection.createArrayOf("uuid", expected.keySet().toArray(UUID[]::new));
            try {
                statement.setObject(1, tenantId);
                statement.setArray(2, ids);
                Set<UUID> current = new LinkedHashSet<>();
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        UUID pointId = rows.getObject("qdrant_point_id", UUID.class);
                        HybridSearchHit hit = expected.get(pointId);
                        if (hit != null
                                && hit.documentId().equals(rows.getObject("document_id", UUID.class))
                                && hit.documentVersionId().equals(rows.getObject("version_id", UUID.class))
                                && hit.contentSha256().equals(rows.getString("content_sha256"))) {
                            current.add(pointId);
                        }
                    }
                }
                connection.commit();
                return Set.copyOf(current);
            } finally {
                ids.free();
            }
        } catch (SQLException exception) {

            throw new IllegalStateException("无法确认企业检索候选当前版本", exception);
        }
    }

    public boolean current(UUID tenantId, RerankedSearchHit hit) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, hit.documentId());
            statement.setObject(3, hit.documentVersionId());
            statement.setObject(4, hit.pointId());
            statement.setString(5, hit.contentSha256());
            boolean current;
            try (var rows = statement.executeQuery()) { current = rows.next() && rows.getBoolean(1); }
            connection.commit();
            return current;
        } catch (SQLException exception) {
            throw new IllegalStateException("无法确认企业证据当前版本", exception);
        }
    }
}
