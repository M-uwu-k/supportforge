package com.supportforge.persistence.postgres;

import com.supportforge.config.DatabaseProperties;
import com.supportforge.knowledge.KnowledgeCategory;
import com.supportforge.knowledge.KnowledgeDocument;
import com.supportforge.objectstorage.StoredObject;
import com.supportforge.persistence.port.KnowledgeDocumentPersistencePort;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;


@Component
@DependsOn("supportForgeFlyway")
@ConditionalOnProperty(
        prefix = "supportforge.persistence",
        name = "knowledge",
        havingValue = "postgres"
)
public class PostgresKnowledgeDocumentAdapter
        implements KnowledgeDocumentPersistencePort {

    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final UUID tenantId;
    private final String documentsTable;
    private final String versionsTable;

    public PostgresKnowledgeDocumentAdapter(
            DataSource dataSource,
            ObjectMapper objectMapper,
            DatabaseProperties databaseProperties,
            @Value("${supportforge.persistence.default-tenant-id:00000000-0000-0000-0000-000000000001}")
            String defaultTenantId
    ) {
        String schema = databaseProperties.schema();
        if (schema == null
                || !SAFE_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException(
                    "PostgreSQL schema 不是安全标识符：" + schema
            );
        }
        try {
            tenantId = UUID.fromString(defaultTenantId);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "默认租户编号必须是UUID：" + defaultTenantId,
                    exception
            );
        }
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.documentsTable = schema + ".knowledge_documents";
        this.versionsTable = schema + ".knowledge_document_versions";
    }

    @Override
    public List<KnowledgeDocument> load() {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            List<KnowledgeDocument> documents = readDocuments(connection);
            connection.commit();
            return documents;
        } catch (SQLException | RuntimeException exception) {
            rollbackQuietly(connection);
            throw new IllegalStateException("读取PostgreSQL知识文档失败", exception);
        } finally {
            closeQuietly(connection);
        }
    }

    private List<KnowledgeDocument> readDocuments(Connection connection) throws SQLException {
        String sql = """
                SELECT d.external_key,
                       d.title,
                       d.category,
                       v.content_text,
                       v.keywords::text AS keywords_json
                  FROM %s d
                  JOIN %s v
                    ON v.tenant_id = d.tenant_id
                   AND v.document_id = d.id
                   AND v.version_no = d.active_version_no
                 WHERE d.tenant_id = ?
                   AND d.status = 'ACTIVE'
                 ORDER BY d.created_at, d.external_key
                """.formatted(documentsTable, versionsTable);

            List<KnowledgeDocument> documents = new ArrayList<>();
            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {
                statement.setObject(1, tenantId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String[] keywords = objectMapper.readValue(
                                resultSet.getString("keywords_json"),
                                String[].class
                        );
                        documents.add(
                                new KnowledgeDocument(
                                        resultSet.getString("external_key"),
                                        resultSet.getString("title"),
                                        KnowledgeCategory.valueOf(
                                                resultSet.getString("category")
                                        ),
                                        resultSet.getString("content_text"),
                                        List.of(keywords)
                                )
                        );
                    }
                }
            }
            return List.copyOf(documents);
    }

    @Override
    public synchronized void save(
            List<KnowledgeDocument> documents
    ) {
        Map<String, KnowledgeDocument> desired =
                normalizeDocuments(documents);
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            lockMutation(connection);
            for (KnowledgeDocument document : desired.values()) {
                upsertDocument(connection, document);
            }
            archiveMissingDocuments(
                    connection,
                    desired.keySet()
            );
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            rollbackQuietly(connection);
            throw new IllegalStateException(
                    "保存PostgreSQL知识文档失败",
                    exception
            );
        } finally {
            closeQuietly(connection);
        }
    }

    @Override
    public List<KnowledgeDocument> update(java.util.function.UnaryOperator<List<KnowledgeDocument>> change) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            lockMutation(connection);

            Map<String, KnowledgeDocument> desired = normalizeDocuments(change.apply(readDocuments(connection)));
            for (KnowledgeDocument document : desired.values()) upsertDocument(connection, document);
            archiveMissingDocuments(connection, desired.keySet());
            connection.commit();
            return List.copyOf(desired.values());
        } catch (SQLException exception) {
            rollbackQuietly(connection);
            throw new IllegalStateException("更新PostgreSQL知识文档失败", exception);
        } catch (RuntimeException exception) {
            rollbackQuietly(connection);
            throw exception;
        } finally {
            closeQuietly(connection);
        }
    }

    private void lockMutation(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            statement.setString(1, documentsTable + ":" + tenantId());
            try (ResultSet ignored = statement.executeQuery()) {

            }
        }
    }

    @Override
    public synchronized void attachRawSource(
            List<String> documentIds,
            StoredObject source
    ) {
        attachRawSource(documentIds, null, source);
    }

    @Override
    public synchronized void attachRawSourceDocuments(List<KnowledgeDocument> documents, StoredObject source) {
        attachRawSource(documents.stream().map(KnowledgeDocument::id).toList(), documents, source);
    }

    private void attachRawSource(List<String> documentIds, List<KnowledgeDocument> expectedDocuments,
                                 StoredObject source) {
        if (source == null) {
            throw new IllegalArgumentException("原始对象信息不能为空");
        }
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }

        String sql = """
                UPDATE %s v
                   SET object_key = COALESCE(v.object_key, ?),
                       raw_checksum_sha256 = COALESCE(v.raw_checksum_sha256, ?),
                       raw_size_bytes = COALESCE(v.raw_size_bytes, ?),
                       source_filename = COALESCE(v.source_filename, ?),
                       content_type = CASE
                           WHEN v.object_key IS NULL THEN ?
                           ELSE v.content_type
                       END
                  FROM %s d
                 WHERE d.tenant_id = ?
                   AND d.external_key = ?
                   AND v.tenant_id = d.tenant_id
                   AND v.document_id = d.id
                   AND %s
                   AND (
                       v.raw_checksum_sha256 IS NULL
                       OR v.raw_checksum_sha256 = ?
                   )
                """.formatted(versionsTable, documentsTable,
                expectedDocuments == null ? "v.version_no = d.active_version_no" : """
                v.id = (SELECT matched.id FROM %s matched
                         WHERE matched.tenant_id = d.tenant_id AND matched.document_id = d.id
                           AND matched.checksum_sha256 = ?
                         ORDER BY matched.version_no DESC LIMIT 1)
                """.formatted(versionsTable));

        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {
                for (int index = 0; index < documentIds.size(); index++) {
                    String documentId = documentIds.get(index);
                    if (documentId == null || documentId.isBlank()) {
                        throw new IllegalArgumentException(
                                "关联原始对象时文档ID不能为空"
                        );
                    }
                    statement.setString(1, source.objectKey());
                    statement.setString(2, source.checksumSha256());
                    statement.setLong(3, source.sizeBytes());
                    statement.setString(4, source.originalFilename());
                    statement.setString(5, source.contentType());
                    statement.setObject(6, tenantId());
                    statement.setString(7, documentId);
                    int rawChecksumParameter = 8;
                    if (expectedDocuments != null) {
                        statement.setString(8, com.supportforge.knowledge.KnowledgeDocumentFingerprint.sha256(expectedDocuments.get(index)));
                        rawChecksumParameter = 9;
                    }
                    statement.setString(rawChecksumParameter, source.checksumSha256());
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException(
                                "知识版本不存在或已关联其他原件："
                                        + documentId
                        );
                    }
                }
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            rollbackQuietly(connection);
            throw new IllegalStateException(
                    "关联PostgreSQL知识原件失败",
                    exception
            );
        } finally {
            closeQuietly(connection);
        }
    }

    private void upsertDocument(
            Connection connection,
            KnowledgeDocument document
    ) throws SQLException {
        UUID stableId = UUID.nameUUIDFromBytes(
                (tenantId() + "/" + document.id())
                        .getBytes(StandardCharsets.UTF_8)
        );
        String insertDocumentSql = """
                INSERT INTO %s (
                    id, tenant_id, external_key, title, category,
                    status, source_type, metadata
                ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', 'APPLICATION', '{}'::jsonb)
                ON CONFLICT (tenant_id, external_key)
                DO UPDATE SET
                    title = EXCLUDED.title,
                    category = EXCLUDED.category,
                    status = 'ACTIVE',
                    updated_at = CURRENT_TIMESTAMP
                """.formatted(documentsTable);
        try (PreparedStatement statement =
                     connection.prepareStatement(insertDocumentSql)) {
            statement.setObject(1, stableId);
            statement.setObject(2, tenantId());
            statement.setString(3, document.id());
            statement.setString(4, document.title());
            statement.setString(5, document.category().name());
            statement.executeUpdate();
        }

        String lockSql = """
                SELECT id, active_version_no
                  FROM %s
                 WHERE tenant_id = ? AND external_key = ?
                 FOR UPDATE
                """.formatted(documentsTable);
        UUID documentId;
        Integer activeVersion;
        try (PreparedStatement statement =
                     connection.prepareStatement(lockSql)) {
            statement.setObject(1, tenantId());
            statement.setString(2, document.id());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException(
                            "写入后未找到知识文档：" + document.id()
                    );
                }
                documentId = resultSet.getObject("id", UUID.class);
                int value = resultSet.getInt("active_version_no");
                activeVersion = resultSet.wasNull() ? null : value;
            }
        }

        String checksum = com.supportforge.knowledge.KnowledgeDocumentFingerprint.sha256(document);
        if (activeVersion != null
                && checksum.equals(
                versionChecksum(
                        connection,
                        documentId,
                        activeVersion
                )
        )) {
            return;
        }

        int nextVersion = nextVersion(connection, documentId);
        String keywordsJson =
                objectMapper.writeValueAsString(document.keywords());
        String insertVersionSql = """
                INSERT INTO %s (
                    id, tenant_id, document_id, version_no,
                    checksum_sha256, content_type, object_key,
                    parser_version, ingestion_status, metadata,
                    content_text, keywords
                ) VALUES (
                    ?, ?, ?, ?, ?, 'text/plain; charset=UTF-8', NULL,
                    'supportforge-inline-v1', 'READY', '{}'::jsonb,
                    ?, ?::jsonb
                )
                """.formatted(versionsTable);
        try (PreparedStatement statement =
                     connection.prepareStatement(insertVersionSql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, tenantId());
            statement.setObject(3, documentId);
            statement.setInt(4, nextVersion);
            statement.setString(5, checksum);
            statement.setString(6, document.content());
            statement.setString(7, keywordsJson);
            statement.executeUpdate();
        }

        String activateSql = """
                UPDATE %s
                   SET active_version_no = ?,
                       status = 'ACTIVE',
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND id = ?
                """.formatted(documentsTable);
        try (PreparedStatement statement =
                     connection.prepareStatement(activateSql)) {
            statement.setInt(1, nextVersion);
            statement.setObject(2, tenantId());
            statement.setObject(3, documentId);
            statement.executeUpdate();
        }
    }

    private String versionChecksum(
            Connection connection,
            UUID documentId,
            int version
    ) throws SQLException {
        String sql = """
                SELECT checksum_sha256
                  FROM %s
                 WHERE tenant_id = ?
                   AND document_id = ?
                   AND version_no = ?
                """.formatted(versionsTable);
        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId());
            statement.setObject(2, documentId);
            statement.setInt(3, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? resultSet.getString(1)
                        : null;
            }
        }
    }

    private int nextVersion(
            Connection connection,
            UUID documentId
    ) throws SQLException {
        String sql = """
                SELECT COALESCE(MAX(version_no), 0) + 1
                  FROM %s
                 WHERE tenant_id = ? AND document_id = ?
                """.formatted(versionsTable);
        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId());
            statement.setObject(2, documentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException(
                            "无法计算知识文档下一版本"
                    );
                }
                return resultSet.getInt(1);
            }
        }
    }

    private void archiveMissingDocuments(
            Connection connection,
            Set<String> desiredKeys
    ) throws SQLException {
        String selectSql = """
                SELECT id, external_key
                  FROM %s
                 WHERE tenant_id = ?
                   AND source_type = 'APPLICATION'
                   AND status = 'ACTIVE'
                 FOR UPDATE
                """.formatted(documentsTable);
        List<UUID> archiveIds = new ArrayList<>();
        try (PreparedStatement statement =
                     connection.prepareStatement(selectSql)) {
            statement.setObject(1, tenantId());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    if (!desiredKeys.contains(
                            resultSet.getString("external_key")
                    )) {
                        archiveIds.add(
                                resultSet.getObject("id", UUID.class)
                        );
                    }
                }
            }
        }

        String archiveSql = """
                UPDATE %s
                   SET status = 'ARCHIVED',
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND id = ?
                """.formatted(documentsTable);
        try (PreparedStatement statement =
                     connection.prepareStatement(archiveSql)) {
            for (UUID archiveId : archiveIds) {
                statement.setObject(1, tenantId());
                statement.setObject(2, archiveId);
                statement.addBatch();
            }
            if (!archiveIds.isEmpty()) {
                statement.executeBatch();
            }
        }
    }

    private static Map<String, KnowledgeDocument> normalizeDocuments(
            List<KnowledgeDocument> documents
    ) {
        if (documents == null) {
            throw new IllegalArgumentException(
                    "知识文档列表不能为空"
            );
        }
        Map<String, KnowledgeDocument> byId =
                new LinkedHashMap<>();
        for (KnowledgeDocument document : documents) {
            if (document == null
                    || document.id() == null
                    || document.id().isBlank()) {
                throw new IllegalArgumentException(
                        "知识文档及其ID不能为空"
                );
            }
            KnowledgeDocument previous =
                    byId.putIfAbsent(document.id(), document);
            if (previous != null
                    && !previous.equals(document)) {
                throw new IllegalArgumentException(
                        "同一知识文档ID出现不同内容："
                                + document.id()
                );
            }
        }
        return byId;
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
}
