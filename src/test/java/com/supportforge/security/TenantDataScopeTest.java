package com.supportforge.security;

import com.supportforge.config.DatabaseProperties;
import com.supportforge.intent.IntentCategory;
import com.supportforge.intent.IntentLearningSample;
import com.supportforge.intent.TemplateEmbeddingIntentRecognizer;
import com.supportforge.persistence.port.IntentLearningPersistencePort;
import com.supportforge.persistence.postgres.PostgresKnowledgeDocumentAdapter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TenantDataScopeTest {
    @Test
    void postgresKnowledgeUsesRequestTenantAndDoesNotKeepTheStartupTenant() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        var adapter = new PostgresKnowledgeDocumentAdapter(source, new ObjectMapper(),
                new DatabaseProperties("jdbc:postgresql://localhost/db", "user", "", "supportforge",
                        10, 1, Duration.ofSeconds(5), true), UUID.randomUUID().toString());
        RequestIdentity a = identity();
        RequestIdentity b = identity();
        TenantContext.callWith(a, adapter::load);
        TenantContext.callWith(b, adapter::load);
        verify(statement).setObject(1, a.tenantId());
        verify(statement).setObject(1, b.tenantId());
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void correctedIntentSamplesAreIsolatedAndRestoreWhenSwitchingTenants() {
        Map<String, Map<String, IntentLearningSample>> samples = new HashMap<>();
        IntentLearningPersistencePort store = new IntentLearningPersistencePort() {
            public String scopeKey() {
                return TenantContext.current().map(value -> value.tenantId().toString()).orElse("default");
            }
            public List<IntentLearningSample> findAll() {
                return List.copyOf(samples.getOrDefault(scopeKey(), Map.of()).values());
            }
            public boolean upsert(String message, IntentCategory intent) {
                samples.computeIfAbsent(scopeKey(), ignored -> new LinkedHashMap<>())
                        .put(message, new IntentLearningSample(message, intent));
                return true;
            }
        };
        var recognizer = new TemplateEmbeddingIntentRecognizer(64, store);
        RequestIdentity a = identity();
        RequestIdentity b = identity();
        TenantContext.callWith(a, () -> recognizer.learn("scope-specific-issue", IntentCategory.REFUND));
        TenantContext.callWith(b, () -> recognizer.learn("scope-specific-issue", IntentCategory.TECHNICAL_LOGIN));
        assertThat(TenantContext.callWith(a, () -> recognizer.recognize("scope-specific-issue")).intent())
                .isEqualTo(IntentCategory.REFUND);
        assertThat(TenantContext.callWith(b, () -> recognizer.recognize("scope-specific-issue")).intent())
                .isEqualTo(IntentCategory.TECHNICAL_LOGIN);
    }

    @Test
    void asynchronousContextIsExplicitAndRestoredAfterNestedCalls() throws Exception {
        RequestIdentity a = identity();
        RequestIdentity b = identity();
        try (var executor = Executors.newSingleThreadExecutor()) {
            assertThat(executor.submit(() -> TenantContext.callWith(a, () -> {
                assertThat(TenantContext.current()).contains(a);
                TenantContext.callWith(b, () -> {
                    assertThat(TenantContext.current()).contains(b);
                    return true;
                });
                return TenantContext.current().orElseThrow();
            })).get()).isEqualTo(a);
            assertThat(executor.submit(TenantContext::current).get()).isEmpty();
        }
    }

    private static RequestIdentity identity() {
        return new RequestIdentity(UUID.randomUUID(), "operator", Set.of(EnterpriseRole.ADMIN), "test-request");
    }
}
