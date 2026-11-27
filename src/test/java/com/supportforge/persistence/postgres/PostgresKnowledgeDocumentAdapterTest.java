package com.supportforge.persistence.postgres;

import com.supportforge.config.DatabaseProperties;
import com.supportforge.knowledge.KnowledgeCategory;
import com.supportforge.knowledge.KnowledgeDocument;
import com.supportforge.objectstorage.StoredObject;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

class PostgresKnowledgeDocumentAdapterTest {

    @Test
    void uploadProvenanceUsesSubmittedDocumentVersion() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        var document = new KnowledgeDocument("concurrent-upload", "较早的文件",
                KnowledgeCategory.GENERAL, "较早的文件正文", List.of("原件"));
        var source = new StoredObject("knowledge", "older-file.md", "a".repeat(64), 100, "text/markdown", "older-file.md");

        adapter(dataSource, "supportforge").attachRawSourceDocuments(List.of(document), source);

        verify(connection).prepareStatement(argThat(sql -> sql.contains("matched.checksum_sha256 = ?")
                && !sql.contains("v.version_no = d.active_version_no")));
        verify(statement).setString(8, com.supportforge.knowledge.KnowledgeDocumentFingerprint.sha256(document));
        verify(statement).setString(9, source.checksumSha256());
        verify(connection).commit();
    }

    @Test
    void loadsActiveVersionIntoDomainDocument() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString()))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("external_key"))
                .thenReturn("postgres-knowledge");
        when(resultSet.getString("title"))
                .thenReturn("数据库知识");
        when(resultSet.getString("category"))
                .thenReturn("TECHNICAL");
        when(resultSet.getString("content_text"))
                .thenReturn("正文内容");
        when(resultSet.getString("keywords_json"))
                .thenReturn("[\"数据库\",\"知识\"]");

        List<KnowledgeDocument> documents =
                adapter(dataSource, "supportforge").load();

        assertEquals(1, documents.size());
        assertEquals("postgres-knowledge",
                documents.getFirst().id());
        assertEquals(KnowledgeCategory.TECHNICAL,
                documents.getFirst().category());
        assertEquals(List.of("数据库", "知识"),
                documents.getFirst().keywords());
        verify(connection).commit();
        verify(connection).close();
    }

    @Test
    void rejectsNullSnapshotBeforeOpeningConnection() throws Exception {
        DataSource dataSource = mock(DataSource.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> adapter(dataSource, "supportforge")
                        .save(null)
        );
        verify(dataSource, never()).getConnection();
    }

    @Test
    void rejectsUnsafeSchema() {
        DataSource dataSource = mock(DataSource.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> adapter(
                        dataSource,
                        "supportforge;drop schema public"
                )
        );
    }

    @Test
    void attachesRawObjectProvenanceToActiveVersion()
            throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString()))
                .thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        adapter(dataSource, "supportforge").attachRawSource(
                List.of("raw-source-document"),
                new StoredObject(
                        "supportforge-knowledge",
                        "tenants/default/knowledge/raw/hash/source.md",
                        "a".repeat(64),
                        128,
                        "text/markdown; charset=UTF-8",
                        "source.md"
                )
        );

        verify(statement).setString(1,
                "tenants/default/knowledge/raw/hash/source.md");
        verify(statement).setString(2, "a".repeat(64));
        verify(statement).setLong(3, 128);
        verify(connection).commit();
        verify(connection).close();
    }

    @Test
    void mutationLocksBeforeReadingAndPreservesBusinessFailureOnRollback() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement lock = mock(PreparedStatement.class);
        PreparedStatement read = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(call ->
                ((String) call.getArgument(0)).contains("pg_advisory_xact_lock") ? lock : read);
        when(lock.executeQuery()).thenReturn(mock(ResultSet.class));
        when(read.executeQuery()).thenReturn(rows);
        var conflict = new com.supportforge.knowledge.KnowledgeConflictException("duplicate");
        assertEquals(conflict, assertThrows(com.supportforge.knowledge.KnowledgeConflictException.class,
                () -> adapter(source, "supportforge").update(current -> { throw conflict; })));
        var order = inOrder(connection, lock, read);
        order.verify(connection).setAutoCommit(false);
        order.verify(lock).executeQuery();
        order.verify(read).executeQuery();
        order.verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
    }

    private PostgresKnowledgeDocumentAdapter adapter(
            DataSource dataSource,
            String schema
    ) {
        return new PostgresKnowledgeDocumentAdapter(
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
                "00000000-0000-0000-0000-000000000001"
        );
    }
}
