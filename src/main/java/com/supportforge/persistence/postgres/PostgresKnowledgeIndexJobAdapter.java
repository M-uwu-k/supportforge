package com.supportforge.persistence.postgres;

import com.supportforge.config.DatabaseProperties;
import com.supportforge.config.KafkaIndexingProperties;
import com.supportforge.indexing.KnowledgeIndexJobPort;
import com.supportforge.indexing.KnowledgeIndexWork;
import com.supportforge.indexing.KnowledgeJobClaim;
import com.supportforge.knowledge.ingestion.KnowledgeChunk;
import com.supportforge.vector.KnowledgeVectorPoint;
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
import java.util.UUID;
import java.util.regex.Pattern;

/** Kafka索引Worker的PostgreSQL任务租约、chunk读取与完成状态Adapter。 */
@Component
@DependsOn("supportForgeFlyway")
@ConditionalOnProperty(
        prefix = "supportforge.features",
        name = "kafka-indexing",
        havingValue = "true"
)
public class PostgresKnowledgeIndexJobAdapter
        implements KnowledgeIndexJobPort {

    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final DataSource dataSource;
    private final KafkaIndexingProperties properties;
    private final String jobsTable;
    private final String documentsTable;
    private final String chunksTable;

    public PostgresKnowledgeIndexJobAdapter(
            DataSource dataSource,
            DatabaseProperties databaseProperties,
            KafkaIndexingProperties properties
    ) {
        String schema = databaseProperties.schema();
        if (schema == null || !SAFE_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException(
                    "PostgreSQL schema 不是安全标识符：" + schema
            );
        }
        this.dataSource = dataSource;
        this.properties = properties;
        this.jobsTable = schema + ".knowledge_ingestion_jobs";
        this.documentsTable = schema + ".knowledge_documents";
        this.chunksTable = schema + ".knowledge_chunks";
    }

    @Override
    public KnowledgeJobClaim claim(
            UUID eventId,
            UUID tenantId,
            UUID documentVersionId,
            String indexVersion,
            String owner,
            String topic,
            int partition,
            long offset
    ) {
        validateClaim(eventId, tenantId, documentVersionId, indexVersion,
                owner, topic, partition, offset);
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            JobRow row = lockJob(
                    connection,
                    tenantId,
                    documentVersionId,
                    indexVersion
            );
            if (row == null) {
                connection.commit();
                return KnowledgeJobClaim.of(KnowledgeJobClaim.Status.MISSING);
            }
            if (row.status().equals("SUCCEEDED")) {
                connection.commit();
                return KnowledgeJobClaim.of(
                        KnowledgeJobClaim.Status.ALREADY_SUCCEEDED
                );
            }
            if (row.attemptCount() >= row.maximumAttempts()) {
                markDead(connection, row.jobId(), "attempts_exhausted");
                connection.commit();
                return KnowledgeJobClaim.of(KnowledgeJobClaim.Status.EXHAUSTED);
            }
            if (!row.available()) {
                connection.commit();
                return KnowledgeJobClaim.of(KnowledgeJobClaim.Status.BUSY);
            }
            acquire(
                    connection, row.jobId(), eventId, owner,
                    topic, partition, offset
            );
            List<KnowledgeChunk> chunks = loadChunks(
                    connection,
                    tenantId,
                    documentVersionId
            );
            if (chunks.isEmpty()) {
                throw new SQLException("知识版本没有可索引chunk");
            }
            connection.commit();
            return KnowledgeJobClaim.acquired(new KnowledgeIndexWork(
                    row.jobId(), eventId, tenantId, row.documentId(),
                    documentVersionId, row.externalDocumentId(),
                    row.category(), indexVersion, owner, chunks
            ));
        } catch (SQLException | RuntimeException exception) {
            rollbackQuietly(connection);
            throw new IllegalStateException("领取知识索引任务失败", exception);
        } finally {
            closeQuietly(connection);
        }
    }

    @Override
    public void markSucceeded(
            KnowledgeIndexWork work,
            List<KnowledgeVectorPoint> points,
            String vectorName
    ) {
        if (work == null || points == null || points.isEmpty()
                || vectorName == null || vectorName.isBlank()) {
            throw new IllegalArgumentException("完成索引任务的参数不能为空");
        }
        if (points.size() != work.chunks().size()) {
            throw new IllegalArgumentException("Point数量与chunk数量不一致");
        }
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            lockOwnedRunningJob(connection, work);
            String updateChunkSql = """
                    UPDATE %s
                       SET embedding_model = ?,
                           embedding_version = ?,
                           qdrant_point_id = ?,
                           index_version = ?,
                           vector_name = ?,
                           index_status = 'INDEXED',
                           indexed_at = CURRENT_TIMESTAMP,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = ?
                       AND document_version_id = ?
                       AND chunk_index = ?
                    """.formatted(chunksTable);
            try (PreparedStatement statement =
                         connection.prepareStatement(updateChunkSql)) {
                for (KnowledgeVectorPoint point : points) {
                    validatePointForWork(work, point);
                    statement.setString(1, point.embeddingModel());
                    statement.setString(2, point.embeddingVersion());
                    statement.setObject(3, point.pointId());
                    statement.setString(4, point.indexVersion());
                    statement.setString(5, vectorName);
                    statement.setObject(6, work.tenantId());
                    statement.setObject(7, work.documentVersionId());
                    statement.setInt(8, point.chunkIndex());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException(
                                "未找到待完成chunk：" + point.chunkIndex()
                        );
                    }
                }
            }
            String completeSql = """
                    UPDATE %s
                       SET status = 'SUCCEEDED',
                           completed_at = CURRENT_TIMESTAMP,
                           lease_owner = NULL,
                           lease_until = NULL,
                           last_error = NULL,
                           failure_code = NULL,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE id = ?
                       AND status = 'RUNNING'
                       AND lease_owner = ?
                       AND lease_until >= CURRENT_TIMESTAMP
                    """.formatted(jobsTable);
            try (PreparedStatement statement =
                         connection.prepareStatement(completeSql)) {
                statement.setObject(1, work.jobId());
                statement.setString(2, work.owner());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("索引任务租约已丢失");
                }
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            rollbackQuietly(connection);
            throw new IllegalStateException("完成知识索引任务失败", exception);
        } finally {
            closeQuietly(connection);
        }
    }

    @Override
    public void markFailed(
            KnowledgeIndexWork work,
            String errorMessage
    ) {
        if (work == null || errorMessage == null || errorMessage.isBlank()) {
            throw new IllegalArgumentException("失败工作单和原因不能为空");
        }
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            Attempt attempt = lockOwnedRunningJob(connection, work);
            boolean dead = attempt.count() >= attempt.maximum();
            String sql = """
                    UPDATE %s
                       SET status = ?,
                           available_at = CURRENT_TIMESTAMP
                               + (? * INTERVAL '1 millisecond'),
                           lease_owner = NULL,
                           lease_until = NULL,
                           last_error = ?,
                           failure_code = ?,
                           completed_at = CASE WHEN ?
                               THEN CURRENT_TIMESTAMP ELSE NULL END,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE id = ?
                       AND status = 'RUNNING'
                       AND lease_owner = ?
                    """.formatted(jobsTable);
            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {
                statement.setString(1, dead ? "DEAD" : "FAILED");
                statement.setLong(
                        2,
                        properties.consumerRetryDelay().toMillis()
                );
                statement.setString(3, abbreviate(errorMessage, 4_000));
                statement.setString(4, dead ? "attempts_exhausted" : "worker_error");
                statement.setBoolean(5, dead);
                statement.setObject(6, work.jobId());
                statement.setString(7, work.owner());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("索引任务租约已丢失");
                }
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            rollbackQuietly(connection);
            throw new IllegalStateException("记录知识索引任务失败状态失败", exception);
        } finally {
            closeQuietly(connection);
        }
    }

    private JobRow lockJob(
            Connection connection,
            UUID tenantId,
            UUID versionId,
            String indexVersion
    ) throws SQLException {
        String sql = """
                SELECT j.id, j.document_id, j.status,
                       j.attempt_count, j.max_attempts,
                       (
                           (j.status IN ('PENDING', 'FAILED')
                               AND j.available_at <= CURRENT_TIMESTAMP)
                           OR (j.status = 'RUNNING'
                               AND j.lease_until < CURRENT_TIMESTAMP)
                       ) AS available,
                       d.external_key, d.category
                  FROM %s j
                  JOIN %s d
                    ON d.tenant_id = j.tenant_id
                   AND d.id = j.document_id
                 WHERE j.tenant_id = ?
                   AND j.document_version_id = ?
                   AND j.index_version = ?
                 FOR UPDATE OF j
                """.formatted(jobsTable, documentsTable);
        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, versionId);
            statement.setString(3, indexVersion);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new JobRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("document_id", UUID.class),
                        resultSet.getString("external_key"),
                        resultSet.getString("category"),
                        resultSet.getString("status"),
                        resultSet.getInt("attempt_count"),
                        resultSet.getInt("max_attempts"),
                        resultSet.getBoolean("available")
                );
            }
        }
    }

    private void acquire(
            Connection connection,
            UUID jobId,
            UUID eventId,
            String owner,
            String topic,
            int partition,
            long offset
    ) throws SQLException {
        String sql = """
                UPDATE %s
                   SET status = 'RUNNING',
                       attempt_count = attempt_count + 1,
                       lease_owner = ?,
                       lease_until = CURRENT_TIMESTAMP
                           + (? * INTERVAL '1 millisecond'),
                       last_event_id = ?,
                       kafka_topic = ?,
                       kafka_partition = ?,
                       kafka_offset = ?,
                       last_error = NULL,
                       failure_code = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """.formatted(jobsTable);
        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setString(1, owner);
            statement.setLong(2, properties.workerLeaseDuration().toMillis());
            statement.setObject(3, eventId);
            statement.setString(4, topic);
            statement.setInt(5, partition);
            statement.setLong(6, offset);
            statement.setObject(7, jobId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("领取索引任务失败");
            }
        }
    }

    private List<KnowledgeChunk> loadChunks(
            Connection connection,
            UUID tenantId,
            UUID versionId
    ) throws SQLException {
        String sql = """
                SELECT chunk_index, content, content_sha256,
                       token_count, start_offset, end_offset
                  FROM %s
                 WHERE tenant_id = ?
                   AND document_version_id = ?
                 ORDER BY chunk_index
                """.formatted(chunksTable);
        List<KnowledgeChunk> chunks = new ArrayList<>();
        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, versionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Integer start = (Integer) resultSet.getObject("start_offset");
                    Integer end = (Integer) resultSet.getObject("end_offset");
                    if (start == null || end == null) {
                        throw new SQLException("知识chunk缺少字符偏移");
                    }
                    chunks.add(new KnowledgeChunk(
                            resultSet.getInt("chunk_index"),
                            resultSet.getString("content"),
                            resultSet.getString("content_sha256"),
                            resultSet.getInt("token_count"),
                            start,
                            end
                    ));
                }
            }
        }
        return List.copyOf(chunks);
    }

    private Attempt lockOwnedRunningJob(
            Connection connection,
            KnowledgeIndexWork work
    ) throws SQLException {
        String sql = """
                SELECT attempt_count, max_attempts
                  FROM %s
                 WHERE id = ?
                   AND status = 'RUNNING'
                   AND lease_owner = ?
                   AND lease_until >= CURRENT_TIMESTAMP
                 FOR UPDATE
                """.formatted(jobsTable);
        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setObject(1, work.jobId());
            statement.setString(2, work.owner());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("索引任务租约不存在、过期或owner不匹配");
                }
                return new Attempt(
                        resultSet.getInt("attempt_count"),
                        resultSet.getInt("max_attempts")
                );
            }
        }
    }

    private void markDead(
            Connection connection,
            UUID jobId,
            String failureCode
    ) throws SQLException {
        String sql = """
                UPDATE %s
                   SET status = 'DEAD',
                       failure_code = ?,
                       completed_at = CURRENT_TIMESTAMP,
                       lease_owner = NULL,
                       lease_until = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """.formatted(jobsTable);
        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setString(1, failureCode);
            statement.setObject(2, jobId);
            statement.executeUpdate();
        }
    }

    private static void validatePointForWork(
            KnowledgeIndexWork work,
            KnowledgeVectorPoint point
    ) {
        if (!point.tenantId().equals(work.tenantId())
                || !point.documentId().equals(work.documentId())
                || !point.documentVersionId().equals(work.documentVersionId())
                || !point.indexVersion().equals(work.indexVersion())) {
            throw new IllegalArgumentException("Point身份与索引工作单不一致");
        }
    }

    private static void validateClaim(
            UUID eventId,
            UUID tenantId,
            UUID versionId,
            String indexVersion,
            String owner,
            String topic,
            int partition,
            long offset
    ) {
        if (eventId == null || tenantId == null || versionId == null
                || indexVersion == null || indexVersion.isBlank()
                || owner == null || owner.isBlank()
                || topic == null || topic.isBlank()
                || partition < 0 || offset < 0) {
            throw new IllegalArgumentException("Kafka索引任务领取参数非法");
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

    private record JobRow(
            UUID jobId,
            UUID documentId,
            String externalDocumentId,
            String category,
            String status,
            int attemptCount,
            int maximumAttempts,
            boolean available
    ) {
    }

    private record Attempt(int count, int maximum) {
    }
}
