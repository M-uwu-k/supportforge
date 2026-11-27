package com.supportforge.persistence.postgres;

import com.supportforge.config.DatabaseProperties;
import com.supportforge.config.QdrantProperties;
import com.supportforge.knowledge.ingestion.KnowledgeChunk;
import com.supportforge.knowledge.KnowledgeDocument;
import com.supportforge.knowledge.KnowledgeDocumentFingerprint;
import com.supportforge.persistence.port.KnowledgeIngestionPersistencePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** PostgreSQL 知识分块 Adapter：业务调用按完整文档指纹绑定版本，原子保存分块与索引任务。 */
@Component
@DependsOn("supportForgeFlyway")
@ConditionalOnProperty(
        prefix = "supportforge.persistence",
        name = "knowledge",
        havingValue = "postgres"
)
public class PostgresKnowledgeIngestionAdapter
        implements KnowledgeIngestionPersistencePort {

    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final UUID tenantId;
    private final String indexVersion;
    private final String documentsTable;
    private final String versionsTable;
    private final String chunksTable;
    private final String jobsTable;
    private final String outboxTable;

    @Override
    public synchronized void replaceChunks(KnowledgeDocument document, String parserVersion,
                                           String chunkerVersion, List<KnowledgeChunk> chunks) {
        replaceChunks(document.id(), KnowledgeDocumentFingerprint.sha256(document),
                parserVersion, chunkerVersion, chunks);
    }

    public PostgresKnowledgeIngestionAdapter(
            DataSource dataSource,
            ObjectMapper objectMapper,
            DatabaseProperties properties,
            QdrantProperties qdrantProperties,
            @Value("${supportforge.persistence.default-tenant-id:00000000-0000-0000-0000-000000000001}")
            String defaultTenantId
    ) {
        String schema = properties.schema();
        if (schema == null
                || !SAFE_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException(
                    "PostgreSQL schema 不是安全标识符：" + schema
            );
        }
        try {
            this.tenantId = UUID.fromString(defaultTenantId);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "默认租户编号必须是UUID：" + defaultTenantId,
                    exception
            );
        }
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.indexVersion = qdrantProperties.indexVersion();
        this.documentsTable = schema + ".knowledge_documents";
        this.versionsTable = schema + ".knowledge_document_versions";
        this.chunksTable = schema + ".knowledge_chunks";
        this.jobsTable = schema + ".knowledge_ingestion_jobs";
        this.outboxTable = schema + ".outbox_events";
    }

    @Override
    public synchronized void replaceChunks(
            String documentId,
            String parserVersion,
            String chunkerVersion,
            List<KnowledgeChunk> chunks
    ) {
        replaceChunks(documentId, null, parserVersion, chunkerVersion, chunks);
    }

    private void replaceChunks(String documentId, String expectedChecksum,
                               String parserVersion, String chunkerVersion, List<KnowledgeChunk> chunks) {
        validate(documentId, parserVersion, chunkerVersion, chunks);
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            ActiveVersion active = lockVersion(
                    connection,
                    documentId,
                    expectedChecksum
            );


            if (matchesExistingChunks(connection, active.versionId(), chunks)) {

                scheduleIndexing(connection, documentId, active, chunks.size());
                connection.commit();
                return;
            }
            markChunking(
                    connection,
                    active.versionId(),
                    parserVersion,
                    chunkerVersion
            );
            deleteExistingChunks(connection, active.versionId());
            insertChunks(
                    connection,
                    active,
                    chunkerVersion,
                    chunks
            );
            markReady(connection, active.versionId());
            scheduleIndexing(
                    connection,
                    documentId,
                    active,
                    chunks.size()
            );
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            rollbackQuietly(connection);
            throw new IllegalStateException(
                    "保存PostgreSQL知识分块失败：" + documentId,
                    exception
            );
        } finally {
            closeQuietly(connection);
        }
    }

    @Override
    public synchronized void markFailed(
            String documentId,
            String failureReason
    ) {
        markFailed(documentId, null, failureReason);
    }

    @Override
    public synchronized void markFailed(KnowledgeDocument document, String failureReason) {
        markFailed(document.id(), KnowledgeDocumentFingerprint.sha256(document), failureReason);
    }

    private void markFailed(String documentId, String expectedChecksum, String failureReason) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("知识文档ID不能为空");
        }
        String sql = """
                UPDATE %s v
                   SET ingestion_status = 'FAILED',
                       failure_reason = ?
                 WHERE v.tenant_id = ? AND v.id = ?
                """.formatted(versionsTable);
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            ActiveVersion target = lockVersion(connection, documentId, expectedChecksum);
            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {
                statement.setString(1, failureReason);
                statement.setObject(2, tenantId());
                statement.setObject(3, target.versionId());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException(
                            "未找到当前知识版本：" + documentId
                    );
                }
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            rollbackQuietly(connection);
            throw new IllegalStateException(
                    "标记知识解析失败时发生错误：" + documentId,
                    exception
            );
        } finally {
            closeQuietly(connection);
        }
    }

    private ActiveVersion lockVersion(
            Connection connection,
            String documentId,
            String expectedChecksum
    ) throws SQLException {
        String sql = """
                SELECT d.id AS document_uuid,
                       v.id AS version_uuid
                  FROM %s d
                  JOIN %s v
                    ON v.tenant_id = d.tenant_id
                   AND v.document_id = d.id
                 WHERE d.tenant_id = ?
                   AND d.external_key = ?
                   AND %s
                 ORDER BY v.version_no DESC
                 LIMIT 1
                 FOR UPDATE OF d, v
                """.formatted(documentsTable, versionsTable,
                expectedChecksum == null ? "v.version_no = d.active_version_no" : "v.checksum_sha256 = ?");
        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId());
            statement.setString(2, documentId);
            if (expectedChecksum != null) statement.setString(3, expectedChecksum);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException(
                            "未找到当前知识版本：" + documentId
                    );
                }
                return new ActiveVersion(
                        resultSet.getObject("document_uuid", UUID.class),
                        resultSet.getObject("version_uuid", UUID.class)
                );
            }
        }
    }

    private void markChunking(
            Connection connection,
            UUID versionId,
            String parserVersion,
            String chunkerVersion
    ) throws SQLException {
        String sql = """
                UPDATE %s
                   SET parser_version = ?,
                       chunker_version = ?,
                       ingestion_status = 'CHUNKING',
                       failure_reason = NULL
                 WHERE tenant_id = ? AND id = ?
                """.formatted(versionsTable);
        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setString(1, parserVersion);
            statement.setString(2, chunkerVersion);
            statement.setObject(3, tenantId());
            statement.setObject(4, versionId);
            statement.executeUpdate();
        }
    }

    private void deleteExistingChunks(
            Connection connection,
            UUID versionId
    ) throws SQLException {
        String sql = """
                DELETE FROM %s
                 WHERE tenant_id = ? AND document_version_id = ?
                """.formatted(chunksTable);
        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId());
            statement.setObject(2, versionId);
            statement.executeUpdate();
        }
    }

    private boolean matchesExistingChunks(
            Connection connection,
            UUID versionId,
            List<KnowledgeChunk> chunks
    ) throws SQLException {
        String sql = """
                SELECT chunk_index, content, content_sha256, token_count,
                       start_offset, end_offset
                  FROM %s
                 WHERE tenant_id = ? AND document_version_id = ?
                 ORDER BY chunk_index
                """.formatted(chunksTable);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId());
            statement.setObject(2, versionId);
            try (ResultSet rows = statement.executeQuery()) {
                int index = 0;
                while (rows.next()) {
                    if (index >= chunks.size()) {
                        return false;
                    }
                    KnowledgeChunk chunk = chunks.get(index++);
                    if (rows.getInt("chunk_index") != chunk.index()
                            || !Objects.equals(rows.getString("content"), chunk.content())
                            || !Objects.equals(rows.getString("content_sha256"), chunk.checksumSha256())
                            || rows.getInt("token_count") != chunk.estimatedTokens()
                            || rows.getInt("start_offset") != chunk.startOffset()
                            || rows.getInt("end_offset") != chunk.endOffset()) {
                        return false;
                    }
                }
                return index == chunks.size();
            }
        }
    }

    private void insertChunks(
            Connection connection,
            ActiveVersion active,
            String chunkerVersion,
            List<KnowledgeChunk> chunks
    ) throws SQLException {
        String sql = """
                INSERT INTO %s (
                    id, tenant_id, document_id, document_version_id,
                    chunk_index, content, content_sha256, token_count,
                    chunker_version, start_offset, end_offset,
                    index_status, metadata
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', '{}'::jsonb
                )
                """.formatted(chunksTable);
        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            for (KnowledgeChunk chunk : chunks) {
                statement.setObject(1, deterministicChunkId(
                        active.versionId(),
                        chunk
                ));
                statement.setObject(2, tenantId());
                statement.setObject(3, active.documentId());
                statement.setObject(4, active.versionId());
                statement.setInt(5, chunk.index());
                statement.setString(6, chunk.content());
                statement.setString(7, chunk.checksumSha256());
                statement.setInt(8, chunk.estimatedTokens());
                statement.setString(9, chunkerVersion);
                statement.setInt(10, chunk.startOffset());
                statement.setInt(11, chunk.endOffset());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void markReady(
            Connection connection,
            UUID versionId
    ) throws SQLException {
        String sql = """
                UPDATE %s
                   SET ingestion_status = 'READY',
                       failure_reason = NULL
                 WHERE tenant_id = ? AND id = ?
                """.formatted(versionsTable);
        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId());
            statement.setObject(2, versionId);
            statement.executeUpdate();
        }
    }

    private void scheduleIndexing(
            Connection connection,
            String externalDocumentId,
            ActiveVersion active,
            int chunkCount
    ) throws SQLException {
        String idempotencyKey = "knowledge-index:"
                + active.versionId() + ":" + indexVersion;
        UUID jobId = UUID.nameUUIDFromBytes(
                (tenantId() + "/" + idempotencyKey + "/job")
                        .getBytes(StandardCharsets.UTF_8)
        );
        String jobSql = """
                INSERT INTO %s (
                    id, tenant_id, document_id, document_version_id,
                    status, attempt_count, available_at,
                    job_type, index_version, idempotency_key, max_attempts
                ) VALUES (
                    ?, ?, ?, ?, 'PENDING', 0, CURRENT_TIMESTAMP,
                    'KNOWLEDGE_INDEX', ?, ?, 10
                )
                ON CONFLICT (tenant_id, document_version_id, index_version)
                DO NOTHING
                """.formatted(jobsTable);
        try (PreparedStatement statement =
                     connection.prepareStatement(jobSql)) {
            statement.setObject(1, jobId);
            statement.setObject(2, tenantId());
            statement.setObject(3, active.documentId());
            statement.setObject(4, active.versionId());
            statement.setString(5, indexVersion);
            statement.setString(6, idempotencyKey);
            statement.executeUpdate();
        }

        String eventType = "knowledge.chunks.ready.v1";
        String eventIdempotency = eventType + ":"
                + active.versionId() + ":" + indexVersion;
        UUID eventId = UUID.nameUUIDFromBytes(
                (tenantId() + "/" + eventIdempotency)
                        .getBytes(StandardCharsets.UTF_8)
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenant_id", tenantId().toString());
        payload.put("document_id", active.documentId().toString());
        payload.put("document_version_id", active.versionId().toString());
        payload.put("external_document_id", externalDocumentId);
        payload.put("index_version", indexVersion);
        payload.put("chunk_count", chunkCount);
        Map<String, Object> headers = Map.of(
                "schema_version", 1,
                "producer", "supportforge"
        );

        String outboxSql = """
                INSERT INTO %s (
                    id, tenant_id, aggregate_type, aggregate_id,
                    aggregate_version, event_type, event_version,
                    topic, partition_key, idempotency_key,
                    payload, headers, status, attempt_count, available_at
                ) VALUES (
                    ?, ?, 'KnowledgeDocumentVersion', ?,
                    1, ?, 1, 'supportforge.knowledge.index.v1', ?, ?,
                    ?::jsonb, ?::jsonb, 'PENDING', 0, CURRENT_TIMESTAMP
                )
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """.formatted(outboxTable);
        try (PreparedStatement statement =
                     connection.prepareStatement(outboxSql)) {
            statement.setObject(1, eventId);
            statement.setObject(2, tenantId());
            statement.setObject(3, active.versionId());
            statement.setString(4, eventType);
            statement.setString(
                    5,
                    tenantId() + ":" + active.documentId()
            );
            statement.setString(6, eventIdempotency);
            statement.setString(
                    7,
                    objectMapper.writeValueAsString(payload)
            );
            statement.setString(
                    8,
                    objectMapper.writeValueAsString(headers)
            );
            statement.executeUpdate();
        }
    }

    private UUID deterministicChunkId(
            UUID versionId,
            KnowledgeChunk chunk
    ) {
        return UUID.nameUUIDFromBytes(
                (tenantId() + "/" + versionId + "/"
                        + chunk.index() + "/"
                        + chunk.checksumSha256())
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void validate(
            String documentId,
            String parserVersion,
            String chunkerVersion,
            List<KnowledgeChunk> chunks
    ) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("知识文档ID不能为空");
        }
        if (parserVersion == null || parserVersion.isBlank()) {
            throw new IllegalArgumentException("parserVersion不能为空");
        }
        if (chunkerVersion == null || chunkerVersion.isBlank()) {
            throw new IllegalArgumentException("chunkerVersion不能为空");
        }
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("知识分块不能为空");
        }
        for (int index = 0; index < chunks.size(); index++) {
            if (chunks.get(index).index() != index) {
                throw new IllegalArgumentException(
                        "知识分块序号必须从0连续递增"
                );
            }
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

    private record ActiveVersion(
            UUID documentId,
            UUID versionId
    ) {
    }


    private UUID tenantId() {
        return com.supportforge.security.TenantContext.current()
                .map(com.supportforge.security.RequestIdentity::tenantId)
                .orElse(tenantId);
    }
}
