SET search_path TO supportforge, public;

-- 第4步在多租户上下文正式落地前使用稳定的系统租户。
-- 固定UUID由默认配置引用；ON CONFLICT保证迁移和环境恢复时保持幂等。
INSERT INTO tenants (
    id,
    tenant_key,
    display_name,
    status,
    metadata
) VALUES (
    '00000000-0000-0000-0000-000000000001',
    'default',
    'Default Tenant',
    'ACTIVE',
    '{"bootstrap":true}'::jsonb
)
ON CONFLICT (id) DO NOTHING;
