SET search_path TO supportforge, public;

CREATE TABLE resource_acl (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    resource_type VARCHAR(100) NOT NULL,
    resource_id VARCHAR(240) NOT NULL,
    principal_id VARCHAR(200) NOT NULL,
    permission VARCHAR(64) NOT NULL,
    granted_by VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_resource_acl_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT uq_resource_acl_grant
        UNIQUE (tenant_id, resource_type, resource_id, principal_id, permission),
    CONSTRAINT ck_resource_acl_permission
        CHECK (permission IN ('READ', 'WRITE', 'ADMIN'))
);

CREATE INDEX ix_resource_acl_lookup
    ON resource_acl(tenant_id, resource_type, resource_id, principal_id);
