package com.supportforge.persistence.postgres;

import com.supportforge.config.DatabaseProperties;
import com.supportforge.config.QdrantProperties;
import com.supportforge.knowledge.ingestion.KnowledgeChunk;
import com.supportforge.knowledge.KnowledgeDocument;
import com.supportforge.knowledge.KnowledgeCategory;
import com.supportforge.knowledge.KnowledgeDocumentFingerprint;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresKnowledgeIngestionAdapterTest {

    @Test
    void chunksAndFailureStatusUseSubmittedVersionInsteadOfLatestVersion() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement lock = mock(PreparedStatement.class);
        PreparedStatement mutations = mock(PreparedStatement.class);
        PreparedStatement failureUpdate = mock(PreparedStatement.class);
        PreparedStatement chunks = mock(PreparedStatement.class);
        ResultSet version = mock(ResultSet.class);
        UUID submittedVersion = UUID.randomUUID();
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.contains("SELECT d.id AS document_uuid")) return lock;
            if (sql.contains("SET ingestion_status = 'FAILED'")) return failureUpdate;
            return sql.contains("SELECT chunk_index, content") ? chunks : mutations;
        });
        when(lock.executeQuery()).thenReturn(version);
        when(version.next()).thenReturn(true);
        when(version.getObject("document_uuid", UUID.class)).thenReturn(UUID.randomUUID());
        when(version.getObject("version_uuid", UUID.class)).thenReturn(submittedVersion);
        when(chunks.executeQuery()).thenReturn(mock(ResultSet.class));
        when(mutations.executeUpdate()).thenReturn(1);
        when(failureUpdate.executeUpdate()).thenReturn(1);
        KnowledgeDocument older = new KnowledgeDocument("document-1", "较早提交的标题",
                KnowledgeCategory.GENERAL, "较早提交的正文", List.of("旧关键词"));
        var adapter = adapter(dataSource, "supportforge");

        adapter.replaceChunks(older, "parser-v1", "chunker-v1", List.of(chunk(0, older.content(), 0, 7)));
        adapter.markFailed(older, "仅更新本次提交的版本");

        verify(lock, org.mockito.Mockito.times(2)).setString(3, KnowledgeDocumentFingerprint.sha256(older));
        verify(connection, org.mockito.Mockito.times(2)).prepareStatement(argThat(sql ->
                sql.contains("SELECT d.id AS document_uuid") && sql.contains("v.checksum_sha256 = ?")
                        && !sql.contains("v.version_no = d.active_version_no")));
        verify(failureUpdate).setObject(3, submittedVersion);
        verify(connection, org.mockito.Mockito.times(2)).commit();
    }

    @Test
    void missingSubmittedVersionNeverOverwritesAnotherVersionsChunks() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(mock(ResultSet.class));
        KnowledgeDocument document = new KnowledgeDocument("document-1", "标题", KnowledgeCategory.GENERAL,
                "未保存的正文", List.of("关键词"));

        assertThrows(IllegalStateException.class, () -> adapter(dataSource, "supportforge")
                .replaceChunks(document, "parser-v1", "chunker-v1", List.of(chunk(0, document.content(), 0, 6))));

        verify(statement, never()).executeUpdate();
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void replaysIdenticalChunksWithoutClearingTheirIndexState() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement normal = mock(PreparedStatement.class);
        PreparedStatement chunksQuery = mock(PreparedStatement.class);
        ResultSet active = mock(ResultSet.class);
        ResultSet existingChunks = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.contains("SELECT chunk_index, content") ? chunksQuery : normal;
        });
        when(normal.executeQuery()).thenReturn(active);
        when(active.next()).thenReturn(true);
        when(active.getObject("document_uuid", UUID.class)).thenReturn(UUID.randomUUID());
        when(active.getObject("version_uuid", UUID.class)).thenReturn(UUID.randomUUID());
        KnowledgeChunk chunk = chunk(0, "已索引正文", 0, 5);
        when(chunksQuery.executeQuery()).thenReturn(existingChunks);
        when(existingChunks.next()).thenReturn(true, false);
        when(existingChunks.getInt("chunk_index")).thenReturn(chunk.index());
        when(existingChunks.getString("content")).thenReturn(chunk.content());
        when(existingChunks.getString("content_sha256")).thenReturn(chunk.checksumSha256());
        when(existingChunks.getInt("token_count")).thenReturn(chunk.estimatedTokens());
        when(existingChunks.getInt("start_offset")).thenReturn(chunk.startOffset());
        when(existingChunks.getInt("end_offset")).thenReturn(chunk.endOffset());

        adapter(dataSource, "supportforge").replaceChunks(
                "document-1", "parser-v1", "chunker-v1", List.of(chunk)
        );


        verify(connection, never()).prepareStatement(argThat(sql ->
                sql.contains("DELETE FROM supportforge.knowledge_chunks")
                        || sql.contains("INSERT INTO supportforge.knowledge_chunks")));

        verify(connection).prepareStatement(argThat(sql -> sql.contains("INSERT INTO supportforge.knowledge_ingestion_jobs")));
        verify(connection).commit();
    }

    @Test
    void replacesChunksAndCommitsOneVersionTransaction()
            throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString()))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getObject("document_uuid", UUID.class))
                .thenReturn(UUID.randomUUID());
        when(resultSet.getObject("version_uuid", UUID.class))
                .thenReturn(UUID.randomUUID());

        adapter(dataSource, "supportforge").replaceChunks(
                "document-1",
                "parser-v1",
                "chunker-v1",
                List.of(
                        chunk(0, "第一块", 0, 3),
                        chunk(1, "第二块", 3, 6)
                )
        );

        verify(statement, org.mockito.Mockito.times(2)).addBatch();
        verify(statement).executeBatch();
        verify(connection, atLeastOnce()).prepareStatement(argThat(sql ->
                sql.contains("knowledge_ingestion_jobs")));
        verify(connection, atLeastOnce()).prepareStatement(argThat(sql ->
                sql.contains("outbox_events")));
        verify(connection).commit();
        verify(connection).close();
    }

    @Test
    void rejectsNonContinuousChunkIndexesBeforeConnection()
            throws Exception {
        DataSource dataSource = mock(DataSource.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> adapter(dataSource, "supportforge")
                        .replaceChunks(
                                "document-1",
                                "parser-v1",
                                "chunker-v1",
                                List.of(chunk(2, "错误序号", 0, 4))
                        )
        );
        verify(dataSource, never()).getConnection();
    }

    @Test
    void rejectsUnsafeSchema() {
        assertThrows(
                IllegalArgumentException.class,
                () -> adapter(
                        mock(DataSource.class),
                        "supportforge;drop schema public"
                )
        );
    }

    @Test
    void rollsBackChunksAndJobWhenOutboxInsertFails()
            throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement normal = mock(PreparedStatement.class);
        PreparedStatement failingOutbox = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.contains("outbox_events") ? failingOutbox : normal;
        });
        when(normal.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getObject("document_uuid", UUID.class))
                .thenReturn(UUID.randomUUID());
        when(resultSet.getObject("version_uuid", UUID.class))
                .thenReturn(UUID.randomUUID());
        when(failingOutbox.executeUpdate())
                .thenThrow(new SQLException("outbox unavailable"));

        assertThrows(
                IllegalStateException.class,
                () -> adapter(dataSource, "supportforge").replaceChunks(
                        "document-rollback", "parser-v1", "chunker-v1",
                        List.of(chunk(0, "事务回滚", 0, 4))
                )
        );

        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    private KnowledgeChunk chunk(
            int index,
            String content,
            int start,
            int end
    ) {
        return new KnowledgeChunk(
                index,
                content,
                Integer.toHexString(index).repeat(64).substring(0, 64),
                3,
                start,
                end
        );
    }

    private PostgresKnowledgeIngestionAdapter adapter(
            DataSource dataSource,
            String schema
    ) {
        return new PostgresKnowledgeIngestionAdapter(
                dataSource,
                new ObjectMapper(),
                new DatabaseProperties(
                        "jdbc:postgresql://localhost:5432/supportforge",
                        "supportforge",
                        "",
                        schema,
                        10,
                        1,
                        Duration.ofSeconds(5),
                        true
                ),
                new QdrantProperties(
                        "memory", "http://localhost:6333", "",
                        "knowledge", "dense", "index-v1", 4,
                        "Cosine", 1, 1, 1, 10,
                        Duration.ofSeconds(1)
                ),
                "00000000-0000-0000-0000-000000000001"
        );
    }
}
