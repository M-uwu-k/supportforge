package com.supportforge.config;

import com.supportforge.intent.IntentLearningStore;
import com.supportforge.knowledge.KnowledgeDocumentStore;
import com.supportforge.objectstorage.FileSystemObjectStorageAdapter;
import com.supportforge.objectstorage.ObjectStoragePort;
import com.supportforge.knowledge.ingestion.NoOpKnowledgeIngestionAdapter;
import com.supportforge.persistence.port.KnowledgeIngestionPersistencePort;
import com.supportforge.embedding.EmbeddingProvider;
import com.supportforge.embedding.MockQwen3EmbeddingProvider;
import com.supportforge.vector.InMemoryVectorIndexAdapter;
import com.supportforge.vector.VectorIndexPort;
import com.supportforge.persistence.port.KnowledgeDocumentPersistencePort;
import com.supportforge.persistence.port.IntentLearningPersistencePort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(
        properties = "supportforge.features.grounding-harness=true"
)
@ActiveProfiles("test")
class RuntimeConfigurationIntegrationTest {

    @Autowired
    private DeploymentProperties deploymentProperties;

    @Autowired
    private FeatureFlagProperties featureFlagProperties;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void bindsDeploymentIdentityFromTestProfile() {

        assertEquals("test", deploymentProperties.environment());
        assertEquals("test-instance", deploymentProperties.instanceId());
    }

    @Test
    void allowsOneFeatureToBeOverriddenWithoutEnablingOthers() {

        assertFalse(featureFlagProperties.postgresPersistence());
        assertFalse(featureFlagProperties.qdrantRetrieval());
        assertFalse(featureFlagProperties.objectStorage());
        assertFalse(featureFlagProperties.kafkaIndexing());
        assertFalse(featureFlagProperties.distributedCache());
        assertFalse(featureFlagProperties.multiTenancy());
        assertTrue(featureFlagProperties.groundingHarness());
        assertTrue(featureFlagProperties.anyEnterpriseFeatureEnabled());
    }

    @Test
    void doesNotCreateDatabaseInfrastructureWhenFeatureIsDisabled() {


        assertTrue(
                applicationContext.getBeansOfType(DataSource.class)
                        .isEmpty()
        );
        assertTrue(
                applicationContext.getBeansOfType(Flyway.class)
                        .isEmpty()
        );
    }

    @Test
    void usesExactlyOneJsonIntentLearningAdapterByDefault() {
        var adapters = applicationContext
                .getBeansOfType(IntentLearningPersistencePort.class);

        assertEquals(1, adapters.size());
        assertTrue(adapters.values().iterator().next()
                instanceof IntentLearningStore);
    }

    @Test
    void usesExactlyOneJsonKnowledgeAdapterByDefault() {
        var adapters = applicationContext
                .getBeansOfType(KnowledgeDocumentPersistencePort.class);

        assertEquals(1, adapters.size());
        assertTrue(adapters.values().iterator().next()
                instanceof KnowledgeDocumentStore);
    }

    @Test
    void usesExactlyOneFilesystemObjectStorageAdapterByDefault() {
        var adapters = applicationContext
                .getBeansOfType(ObjectStoragePort.class);

        assertEquals(1, adapters.size());
        assertTrue(adapters.values().iterator().next()
                instanceof FileSystemObjectStorageAdapter);
    }

    @Test
    void usesExactlyOneNoOpChunkPersistenceAdapterInJsonMode() {
        var adapters = applicationContext
                .getBeansOfType(KnowledgeIngestionPersistencePort.class);

        assertEquals(1, adapters.size());
        assertTrue(adapters.values().iterator().next()
                instanceof NoOpKnowledgeIngestionAdapter);
    }

    @Test
    void usesExactlyOneMockEmbeddingProviderByDefault() {
        var providers = applicationContext
                .getBeansOfType(EmbeddingProvider.class);

        assertEquals(1, providers.size());
        assertTrue(providers.values().iterator().next()
                instanceof MockQwen3EmbeddingProvider);
    }

    @Test
    void usesExactlyOneInMemoryVectorIndexByDefault() {
        var indexes = applicationContext
                .getBeansOfType(VectorIndexPort.class);

        assertEquals(1, indexes.size());
        assertTrue(indexes.values().iterator().next()
                instanceof InMemoryVectorIndexAdapter);
    }
}

