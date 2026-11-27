package com.supportforge.persistence.postgres;

import com.supportforge.config.DatabaseProperties;
import com.supportforge.intent.IntentCategory;
import com.supportforge.intent.IntentLearningSample;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresIntentLearningAdapterTest {

    @Test
    void readsEnabledSamplesAndCommitsReadTransaction()
            throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement =
                mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString()))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("message"))
                .thenReturn("申请退款", "登录不了");
        when(resultSet.getString("correct_intent"))
                .thenReturn("refund", "technical_login");

        PostgresIntentLearningAdapter adapter =
                adapter(dataSource, 10);

        List<IntentLearningSample> samples =
                adapter.findAll();

        assertEquals(2, samples.size());
        assertEquals(IntentCategory.REFUND,
                samples.get(0).category());
        assertEquals(IntentCategory.TECHNICAL_LOGIN,
                samples.get(1).category());
        verify(connection).commit();
        verify(connection).close();
    }

    @Test
    void returnsFalseForAnUnchangedDatabaseRow()
            throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement upsert =
                mock(PreparedStatement.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString()))
                .thenReturn(upsert);
        when(upsert.executeUpdate()).thenReturn(0);

        boolean changed = adapter(dataSource, 10)
                .upsert("申请退款", IntentCategory.REFUND);

        assertFalse(changed);
        verify(connection, times(1))
                .prepareStatement(anyString());
        verify(connection).commit();
        verify(connection).close();
    }

    @Test
    void trimsOldRowsAfterAChangedUpsert()
            throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement upsert =
                mock(PreparedStatement.class);
        PreparedStatement trim =
                mock(PreparedStatement.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString()))
                .thenReturn(upsert, trim);
        when(upsert.executeUpdate()).thenReturn(1);

        boolean changed = adapter(dataSource, 2)
                .upsert("申请退款", IntentCategory.REFUND);

        assertTrue(changed);
        verify(trim).setInt(3, 2);
        verify(trim).executeUpdate();
        verify(connection).commit();
        verify(connection, never()).rollback();
        verify(connection).close();
    }

    @Test
    void rejectsInvalidTenantIdBeforeOpeningAConnection() {
        DataSource dataSource = mock(DataSource.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> new PostgresIntentLearningAdapter(
                        dataSource,
                        properties("supportforge"),
                        "not-a-uuid",
                        10
                )
        );
    }

    @Test
    void rejectsUnsafeSchemaBeforeBuildingSql() {
        DataSource dataSource = mock(DataSource.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> new PostgresIntentLearningAdapter(
                        dataSource,
                        properties("supportforge;drop schema public"),
                        "00000000-0000-0000-0000-000000000001",
                        10
                )
        );
    }

    private PostgresIntentLearningAdapter adapter(
            DataSource dataSource,
            int maxSamples
    ) {
        return new PostgresIntentLearningAdapter(
                dataSource,
                properties("supportforge"),
                "00000000-0000-0000-0000-000000000001",
                maxSamples
        );
    }

    private DatabaseProperties properties(String schema) {
        return new DatabaseProperties(
                "jdbc:postgresql://localhost:5432/supportforge",
                "supportforge",
                "",
                schema,
                10,
                1,
                Duration.ofSeconds(5),
                true
        );
    }
}
