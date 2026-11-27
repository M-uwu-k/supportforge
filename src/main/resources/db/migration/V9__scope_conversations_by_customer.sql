SET search_path TO supportforge, public;

-- JSON 记忆一直使用 user_id + conversation_id 联合键。
-- V1 只按 tenant + external_conversation_id 唯一，会错误阻止两个用户使用同一个会话编号。
ALTER TABLE conversations
    DROP CONSTRAINT uq_conversations_external;

ALTER TABLE conversations
    ADD CONSTRAINT uq_conversations_customer_external
        UNIQUE (tenant_id, customer_id, external_conversation_id);
