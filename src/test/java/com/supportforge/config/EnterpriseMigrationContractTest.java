package com.supportforge.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class EnterpriseMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1__create_enterprise_core_schema.sql";
    private static final String DEFAULT_TENANT_MIGRATION =
            "db/migration/V2__seed_default_tenant.sql";
    private static final String KNOWLEDGE_CONTENT_MIGRATION =
            "db/migration/V3__add_knowledge_version_content.sql";
    private static final String RAW_OBJECT_MIGRATION =
            "db/migration/V4__add_raw_object_provenance.sql";
    private static final String CHUNK_PIPELINE_MIGRATION =
            "db/migration/V5__add_chunk_pipeline_metadata.sql";
    private static final String VECTOR_INDEX_MIGRATION =
            "db/migration/V6__add_vector_index_versioning.sql";
    private static final String OUTBOX_MIGRATION =
            "db/migration/V7__harden_transactional_outbox.sql";
    private static final String KAFKA_JOB_MIGRATION =
            "db/migration/V8__add_kafka_job_delivery_metadata.sql";

    @Test
    void definesAllEnterpriseCoreTables() throws IOException {

        String sql = loadMigration().toLowerCase();
        List<String> tables = List.of(
                "tenants",
                "customer_identities",
                "conversations",
                "conversation_messages",
                "conversation_summaries",
                "user_facts",
                "knowledge_documents",
                "knowledge_document_versions",
                "knowledge_chunks",
                "knowledge_ingestion_jobs",
                "answer_records",
                "answer_feedback",
                "evaluation_reports",
                "intent_learning_samples",
                "retrieval_traces",
                "outbox_events",
                "audit_events"
        );

        for (String table : tables) {

            assertTrue(
                    sql.contains("create table " + table),
                    () -> "缺少企业表：" + table
            );
        }
    }

    @Test
    void migrationContainsTenantAndEnterpriseDataContracts()
            throws IOException {

        String sql = loadMigration().toLowerCase();

        assertTrue(sql.contains("tenant_id uuid not null"));
        assertTrue(sql.contains("jsonb"));
        assertTrue(sql.contains("timestamptz"));
        assertTrue(sql.contains("create table outbox_events"));
        assertTrue(sql.contains("create table audit_events"));
    }

    @Test
    void initialMigrationDoesNotContainDestructiveTableCommands()
            throws IOException {

        String sql = loadMigration().toLowerCase();

        assertFalse(sql.contains("drop table"));
        assertFalse(sql.contains("truncate table"));
    }

    @Test
    void seedsStableDefaultTenantIdempotently() throws IOException {
        String sql = loadMigration(DEFAULT_TENANT_MIGRATION)
                .toLowerCase();

        assertTrue(sql.contains(
                "00000000-0000-0000-0000-000000000001"
        ));
        assertTrue(sql.contains("insert into tenants"));
        assertTrue(sql.contains("on conflict (id) do nothing"));
        assertFalse(sql.contains("delete from"));
        assertFalse(sql.contains("drop table"));
    }

    @Test
    void addsVersionedKnowledgeContentWithoutDestructiveRewrite()
            throws IOException {
        String sql = loadMigration(KNOWLEDGE_CONTENT_MIGRATION)
                .toLowerCase();

        assertTrue(sql.contains("alter table knowledge_document_versions"));
        assertTrue(sql.contains("content_text text not null"));
        assertTrue(sql.contains("keywords jsonb not null"));
        assertTrue(sql.contains("jsonb_typeof(keywords) = 'array'"));
        assertFalse(sql.contains("drop table"));
        assertFalse(sql.contains("truncate table"));
    }

    @Test
    void addsRawObjectProvenanceWithoutStoringFileBytes()
            throws IOException {
        String sql = loadMigration(RAW_OBJECT_MIGRATION)
                .toLowerCase();

        assertTrue(sql.contains("raw_checksum_sha256 char(64)"));
        assertTrue(sql.contains("raw_size_bytes bigint"));
        assertTrue(sql.contains("source_filename text"));
        assertTrue(sql.contains("object_key"));
        assertFalse(sql.contains("bytea"));
        assertFalse(sql.contains("drop table"));
        assertFalse(sql.contains("truncate table"));
    }

    @Test
    void addsVersionedChunkPipelineMetadataSafely()
            throws IOException {
        String sql = loadMigration(CHUNK_PIPELINE_MIGRATION)
                .toLowerCase();

        assertTrue(sql.contains("chunker_version varchar(100)"));
        assertTrue(sql.contains("start_offset integer"));
        assertTrue(sql.contains("end_offset integer"));
        assertTrue(sql.contains("end_offset > start_offset"));
        assertFalse(sql.contains("drop table"));
        assertFalse(sql.contains("truncate table"));
    }

    @Test
    void addsVectorIndexGenerationWithoutDestructiveRewrite()
            throws IOException {
        String sql = loadMigration(VECTOR_INDEX_MIGRATION)
                .toLowerCase();

        assertTrue(sql.contains("index_version varchar(100)"));
        assertTrue(sql.contains("vector_name varchar(100)"));
        assertTrue(sql.contains("create table vector_index_generations"));
        assertTrue(sql.contains("where status = 'active'"));
        assertFalse(sql.contains("drop table"));
        assertFalse(sql.contains("truncate table"));
    }

    @Test
    void hardensOutboxAndIndexJobsForLeasedIdempotentWork()
            throws IOException {
        String sql = loadMigration(OUTBOX_MIGRATION).toLowerCase();

        assertTrue(sql.contains("idempotency_key varchar(255)"));
        assertTrue(sql.contains("lease_owner varchar(200)"));
        assertTrue(sql.contains("lease_until timestamptz"));
        assertTrue(sql.contains("aggregate_version bigint"));
        assertTrue(sql.contains("uq_ingestion_jobs_generation"));
        assertTrue(sql.contains("where status in ('pending', 'failed', 'publishing')"));
        assertFalse(sql.contains("drop table"));
        assertFalse(sql.contains("truncate table"));
    }

    @Test
    void addsKafkaDeliveryPositionAndDeadJobState()
            throws IOException {
        String sql = loadMigration(KAFKA_JOB_MIGRATION).toLowerCase();

        assertTrue(sql.contains("last_event_id uuid"));
        assertTrue(sql.contains("kafka_partition integer"));
        assertTrue(sql.contains("kafka_offset bigint"));
        assertTrue(sql.contains("'dead'"));
        assertTrue(sql.contains("kafka_partition >= 0"));
        assertFalse(sql.contains("drop table"));
        assertFalse(sql.contains("truncate table"));
    }


    private String loadMigration() throws IOException {
        return loadMigration(MIGRATION);
    }

    private String loadMigration(String resource) throws IOException {
        ClassLoader classLoader =
                EnterpriseMigrationContractTest.class.getClassLoader();
        try (InputStream stream =
                     classLoader.getResourceAsStream(resource)) {

            assertNotNull(stream, "Flyway迁移文件必须进入测试类路径");
            return new String(
                    stream.readAllBytes(),
                    StandardCharsets.UTF_8
            );
        }
    }
}

