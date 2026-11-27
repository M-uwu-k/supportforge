-- 第11步：记录Kafka投递身份与终态，支持幂等消费、DLT审计和故障恢复。
ALTER TABLE knowledge_ingestion_jobs
    ADD COLUMN last_event_id UUID,
    ADD COLUMN kafka_topic VARCHAR(255),
    ADD COLUMN kafka_partition INTEGER,
    ADD COLUMN kafka_offset BIGINT,
    ADD COLUMN failure_code VARCHAR(100);

ALTER TABLE knowledge_ingestion_jobs
    DROP CONSTRAINT ck_knowledge_ingestion_jobs_status,
    ADD CONSTRAINT ck_knowledge_ingestion_jobs_status
        CHECK (status IN (
            'PENDING', 'RUNNING', 'SUCCEEDED',
            'FAILED', 'DEAD', 'CANCELLED'
        )),
    ADD CONSTRAINT ck_ingestion_jobs_kafka_position
        CHECK (
            (kafka_partition IS NULL AND kafka_offset IS NULL)
            OR (kafka_partition >= 0 AND kafka_offset >= 0)
        );

CREATE INDEX ix_ingestion_jobs_event
    ON knowledge_ingestion_jobs(tenant_id, last_event_id)
    WHERE last_event_id IS NOT NULL;
