SET search_path TO supportforge, public;

DROP INDEX uq_user_facts_active;

ALTER TABLE user_facts
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN supersedes_fact_id UUID;

ALTER TABLE user_facts
    ADD CONSTRAINT fk_user_facts_supersedes
        FOREIGN KEY (tenant_id, supersedes_fact_id)
        REFERENCES user_facts(tenant_id, id),
    ADD CONSTRAINT ck_user_facts_status
        CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'REVOKED'));

CREATE UNIQUE INDEX uq_user_facts_active
    ON user_facts(tenant_id, customer_id, fact_key)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_user_facts_customer_active
    ON user_facts(tenant_id, customer_id, fact_key)
    WHERE status = 'ACTIVE';
