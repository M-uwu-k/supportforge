package com.supportforge.memory;

import com.supportforge.config.DatabaseProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresConversationMemoryTest {

    @Test
    void rejectsNullMessageBeforeOpeningDatabaseConnection() {
        DataSource dataSource = mock(DataSource.class);
        PostgresConversationMemory memory = memory(dataSource, 12, 4);

        assertThrows(
                IllegalArgumentException.class,
                () -> memory.append("user-1", "conversation-1", null)
        );
    }

    @Test
    void clearsMemoryWithoutDeletingConversationAnswersOrFeedback() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        PreparedStatement mutation = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        UUID conversation = UUID.randomUUID();
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(argThat(sql ->
                sql != null && sql.contains("SELECT c.id FROM supportforge.conversations")
                        && sql.contains("customer_identities")
                        && sql.contains("external_user_id")
                        && sql.contains("external_conversation_id")
        ))).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getObject("id", UUID.class)).thenReturn(conversation);
        when(connection.prepareStatement(argThat(sql -> sql != null && (sql.startsWith("UPDATE") || sql.startsWith("DELETE")))))
                .thenReturn(mutation);

        memory(dataSource, 12, 4).clear("user-1", "conversation-1");

        verify(statement).setString(2, "user-1");
        verify(statement).setString(3, "conversation-1");
        verify(connection, never()).prepareStatement(argThat(sql ->
                sql.startsWith("DELETE FROM supportforge.conversations ")
                        || sql.startsWith("DELETE FROM supportforge.answer_records")
                        || sql.startsWith("DELETE FROM supportforge.answer_feedback")));
        verify(connection).prepareStatement(argThat(sql -> sql.contains("request_message_id = NULL")));
        for (String table : java.util.List.of("memory_episodes", "conversation_summaries", "conversation_messages")) {
            verify(connection).prepareStatement(argThat(sql -> sql.startsWith("DELETE FROM supportforge." + table + " ")));
        }
        verify(mutation, times(4)).setObject(2, conversation);
        verify(connection).commit();
    }

    @Test
    void clearingMissingConversationDoesNotChangeOtherData() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(mock(ResultSet.class));
        memory(dataSource, 12, 4).clear("missing", "missing");
        verify(statement, never()).executeUpdate();
        verify(connection).commit();
    }

    @Test
    void clearingFailureRollsBackMessageLinksAndMemoryTogether() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
        when(statement.executeUpdate()).thenReturn(1).thenThrow(new SQLException("clear failed"));
        assertThrows(IllegalStateException.class,
                () -> memory(dataSource, 12, 4).clear("user-1", "conversation-1"));
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void rejectsCompressionBatchLargerThanRecentWindow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> memory(mock(DataSource.class), 3, 4)
        );
    }

    private PostgresConversationMemory memory(
            DataSource dataSource, int maxMessages, int compressionBatchSize
    ) {
        return new PostgresConversationMemory(
                dataSource,
                new ObjectMapper(),
                ConversationSummarizer.local(),
                RelevantHistoryRetriever.defaults(),
                new EpisodicMemoryRetriever(3, 0.15),
                UserProfileExtractor.defaults(),
                new DatabaseProperties(
                        "jdbc:postgresql://localhost:5432/supportforge",
                        "supportforge", "supportforge", "supportforge",
                        10, 1, Duration.ofSeconds(1), true
                ),
                "00000000-0000-0000-0000-000000000001",
                1000,
                maxMessages,
                compressionBatchSize
        );
    }
}
