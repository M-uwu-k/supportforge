package com.supportforge.migration;

import com.supportforge.config.DatabaseProperties;
import com.supportforge.evaluation.EvalReport;
import com.supportforge.feedback.AnswerFeedback;
import com.supportforge.feedback.AnswerRecord;
import com.supportforge.intent.IntentLearningSample;
import com.supportforge.knowledge.KnowledgeDocument;
import com.supportforge.memory.MemoryConversationState;
import com.supportforge.memory.MemoryMessage;
import com.supportforge.security.TenantContextProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 旧JSON到PostgreSQL的受控迁移器。
 * 每个文件先解析和哈希，再以批次事务导入；已有业务键只记录SKIPPED，不覆盖线上数据。
 */
@Service
@DependsOn("supportForgeFlyway")
@ConditionalOnProperty(
        prefix = "supportforge.features",
        name = "postgres-persistence",
        havingValue = "true"
)
public class LegacyJsonMigrationService {
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final LegacyJsonMigrationParser parser;
    private final TenantContextProvider tenants;
    private final String schema;

    public LegacyJsonMigrationService(
            DataSource dataSource,
            ObjectMapper objectMapper,
            LegacyJsonMigrationParser parser,
            TenantContextProvider tenants,
            DatabaseProperties database
    ) {
        if (!database.schema().matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("迁移数据库schema名称不安全");
        }
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.parser = parser;
        this.tenants = tenants;
        this.schema = database.schema();
    }

    public LegacyMigrationPreview preview(LegacyMigrationRequest request) {
        ParsedLegacyDataset parsed = parser.parse(
                request.datasetType(), request.sourceFile()
        );
        Map<String, Integer> byKind = new LinkedHashMap<>();
        parsed.items().forEach(item -> byKind.merge(item.kind().name(), 1, Integer::sum));
        List<String> warnings = new ArrayList<>();
        if (parsed.items().isEmpty()) {
            warnings.add("源文件合法但没有可迁移条目");
        }
        if (parsed.type() == LegacyDatasetType.KNOWLEDGE) {
            warnings.add("知识元数据导入后必须运行解析/分块/向量重建，Qdrant不是导入事实源");
        }
        if (parsed.type() == LegacyDatasetType.FEEDBACK) {
            warnings.add("反馈引用的会话不存在时会创建可回滚的占位会话");
        }
        return new LegacyMigrationPreview(
                parsed.type(), parsed.sourcePath().toString(), parsed.sourceSha256(),
                parsed.sourceSizeBytes(), parsed.items().size(), byKind, warnings
        );
    }

    public LegacyMigrationRun migrate(LegacyMigrationRequest request) {
        UUID tenantId = tenants.tenantId();
        ParsedLegacyDataset parsed = parser.parse(
                request.datasetType(), request.sourceFile()
        );
        LegacyMigrationRun existing = findCompleted(
                tenantId, parsed.type(), parsed.sourceSha256()
        );
        if (existing != null) {
            return existing;
        }
        UUID runId = UUID.randomUUID();
        LegacyMigrationRun raced = insertRun(runId, tenantId, parsed);
        if (raced != null) {
            return raced;
        }
        try (Connection connection = dataSource.getConnection()) {
            try {
                int imported = 0;
                int skipped = 0;
                for (LegacySourceItem item : parsed.items()) {
                    ImportOutcome outcome;
                    if (activeFingerprintExists(connection, tenantId, parsed.type(), item.itemSha256())) {
                        outcome = ImportOutcome.skipped("MIGRATION_ITEM", null, "相同条目已由其他活动批次导入");
                    } else {
                        outcome = importItem(connection, tenantId, runId, item);
                    }
                    insertItem(connection, tenantId, runId, parsed.type(), item, outcome);
                    if (outcome.imported()) {
                        imported++;
                    } else {
                        skipped++;
                    }
                }
                completeRun(connection, runId, imported, skipped);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                markFailed(runId, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            markFailed(runId, exception);
            throw new IllegalStateException("执行JSON迁移失败", exception);
        }
        return get(runId);
    }

    public LegacyMigrationRun get(UUID runId) {
        UUID tenantId = tenants.tenantId();
        String sql = "SELECT * FROM %s.legacy_migration_runs WHERE id=? AND tenant_id=?"
                .formatted(schema);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, runId);
            statement.setObject(2, tenantId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("迁移批次不存在：" + runId);
                }
                LegacyMigrationRun run = mapRun(result);
                connection.commit();
                return run;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取迁移批次失败", exception);
        }
    }

    public LegacyMigrationVerification verify(UUID runId) {
        LegacyMigrationRun run = get(runId);
        ParsedLegacyDataset parsed = parser.parse(run.datasetType(), run.sourceFile());
        Map<String, LegacySourceItem> currentItems = new LinkedHashMap<>();
        parsed.items().forEach(item -> currentItems.put(item.sourceKey(), item));
        String sql = """
                SELECT source_key, item_sha256, status, target_type, target_id
                  FROM %s.legacy_migration_items
                 WHERE run_id=? AND tenant_id=? ORDER BY source_key
                """.formatted(schema);
        int recordedImported = 0;
        int existingTargets = 0;
        List<String> missing = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, runId);
            statement.setObject(2, run.tenantId());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String key = result.getString("source_key");
                    String expectedHash = result.getString("item_sha256");
                    LegacySourceItem sourceItem = currentItems.get(key);
                    if (sourceItem == null || !expectedHash.equals(sourceItem.itemSha256())) {
                        missing.add(key + "（源条目缺失或内容已变化）");
                    }
                    String targetType = result.getString("target_type");
                    UUID targetId = result.getObject("target_id", UUID.class);
                    if ("IMPORTED".equals(result.getString("status"))) {
                        recordedImported++;
                        if (targetExists(connection, run.tenantId(), targetType, targetId)) {
                            existingTargets++;
                            if (sourceItem != null && !targetMatches(
                                    connection, run.tenantId(), targetType, targetId, sourceItem
                            )) {
                                missing.add(key + "（目标内容与源JSON不一致）");
                            }
                        } else {
                            missing.add(key + "（目标行缺失）");
                        }
                    } else if (targetId != null && sourceItem != null
                            && !targetMatches(connection, run.tenantId(), targetType, targetId, sourceItem)) {
                        missing.add(key + "（跳过的线上记录与源JSON不同，需人工确认）");
                    }
                }
            }
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("校验迁移批次失败", exception);
        }
        boolean checksumMatches = run.sourceSha256().equals(parsed.sourceSha256());
        boolean countsMatch = recordedImported == existingTargets;
        return new LegacyMigrationVerification(
                runId, checksumMatches && countsMatch && missing.isEmpty(),
                checksumMatches, countsMatch,
                recordedImported, existingTargets, missing, run.status()
        );
    }

    public LegacyMigrationRun rollback(UUID runId) {
        LegacyMigrationRun run = get(runId);
        if (!"COMPLETED".equals(run.status())) {
            throw new IllegalStateException("只有COMPLETED批次可以回滚，当前状态=" + run.status());
        }
        String[] deletes = {
                "DELETE FROM %s.evaluation_reports WHERE tenant_id=? AND migration_run_id=?",
                "DELETE FROM %s.answer_records WHERE tenant_id=? AND migration_run_id=?",
                "DELETE FROM %s.intent_learning_samples WHERE tenant_id=? AND migration_run_id=?",
                "DELETE FROM %s.knowledge_documents WHERE tenant_id=? AND migration_run_id=?",
                "DELETE FROM %s.conversations WHERE tenant_id=? AND migration_run_id=?",
                """
                DELETE FROM %s.customer_identities u
                 WHERE u.tenant_id=? AND u.migration_run_id=?
                   AND NOT EXISTS (SELECT 1 FROM %s.conversations c
                                    WHERE c.tenant_id=u.tenant_id AND c.customer_id=u.id)
                   AND NOT EXISTS (SELECT 1 FROM %s.user_facts f
                                    WHERE f.tenant_id=u.tenant_id AND f.customer_id=u.id)
                """
        };
        try (Connection connection = dataSource.getConnection()) {
            try {
                for (int index = 0; index < deletes.length; index++) {
                    String sql = index == deletes.length - 1
                            ? deletes[index].formatted(schema, schema, schema)
                            : deletes[index].formatted(schema);
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setObject(1, run.tenantId());
                        statement.setObject(2, runId);
                        statement.executeUpdate();
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE %s.legacy_migration_items SET status='ROLLED_BACK' WHERE run_id=? AND status='IMPORTED'"
                                .formatted(schema))) {
                    statement.setObject(1, runId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE %s.legacy_migration_runs SET status='ROLLED_BACK', rolled_back_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=?"
                                .formatted(schema))) {
                    statement.setObject(1, runId);
                    statement.setObject(2, run.tenantId());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("迁移批次状态发生并发变化");
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("回滚迁移批次失败", exception);
        }
        return get(runId);
    }

    private ImportOutcome importItem(
            Connection connection, UUID tenantId, UUID runId, LegacySourceItem item
    ) throws SQLException {
        return switch (item.kind()) {
            case MEMORY_PROFILE -> importProfile(
                    connection, tenantId, runId, (LegacyUserProfile) item.payload()
            );
            case MEMORY_CONVERSATION -> importConversation(
                    connection, tenantId, runId, (MemoryConversationState) item.payload()
            );
            case KNOWLEDGE_DOCUMENT -> importKnowledge(
                    connection, tenantId, runId, (KnowledgeDocument) item.payload()
            );
            case INTENT_SAMPLE -> importIntent(
                    connection, tenantId, runId, (IntentLearningSample) item.payload()
            );
            case FEEDBACK_ANSWER -> importAnswer(
                    connection, tenantId, runId, (AnswerRecord) item.payload()
            );
            case FEEDBACK_RATING -> importFeedback(
                    connection, tenantId, (AnswerFeedback) item.payload()
            );
            case EVALUATION_REPORT -> importEvaluation(
                    connection, tenantId, runId, item.sourceKey(), (EvalReport) item.payload()
            );
        };
    }

    private ImportOutcome importProfile(
            Connection connection, UUID tenantId, UUID runId, LegacyUserProfile profile
    ) throws SQLException {
        Customer customer = findCustomer(connection, tenantId, profile.userId());
        if (customer != null) {
            return ImportOutcome.skipped("CUSTOMER", customer.id(), "用户已存在，未覆盖线上画像");
        }
        UUID id = insertCustomer(connection, tenantId, runId, profile.userId(), profile.profile());
        return ImportOutcome.imported("CUSTOMER", id);
    }

    private ImportOutcome importConversation(
            Connection connection, UUID tenantId, UUID runId, MemoryConversationState state
    ) throws SQLException {
        Customer customer = findCustomer(connection, tenantId, state.userId());
        if (customer == null) {
            customer = new Customer(insertCustomer(
                    connection, tenantId, runId, state.userId(), state.userProfile()
            ), true);
        }
        UUID existing = findConversation(
                connection, tenantId, customer.id(), state.conversationId()
        );
        if (existing != null) {
            return ImportOutcome.skipped("CONVERSATION", existing, "会话已存在，未合并或覆盖消息");
        }
        UUID conversationId = UUID.randomUUID();
        Instant updatedAt = state.updatedAt().equals(Instant.EPOCH)
                ? Instant.now() : state.updatedAt();
        String sql = """
                INSERT INTO %s.conversations (
                    id, tenant_id, customer_id, external_conversation_id,
                    metadata, started_at, last_message_at, created_at, updated_at,
                    migration_run_id
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                """.formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, conversationId);
            statement.setObject(2, tenantId);
            statement.setObject(3, customer.id());
            statement.setString(4, state.conversationId());
            statement.setString(5, objectMapper.writeValueAsString(
                    Map.of("legacy_json", true)
            ));
            statement.setTimestamp(6, Timestamp.from(updatedAt));
            statement.setTimestamp(7, Timestamp.from(updatedAt));
            statement.setTimestamp(8, Timestamp.from(updatedAt));
            statement.setTimestamp(9, Timestamp.from(updatedAt));
            statement.setObject(10, runId);
            statement.executeUpdate();
        }
        insertMessages(connection, tenantId, conversationId, state.recentMessages());
        if (!state.summary().isBlank()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO %s.conversation_summaries (
                        id, tenant_id, conversation_id, version_no, summary,
                        source_message_count, model_name, metadata, created_at
                    ) VALUES (?, ?, ?, 1, ?, 0, 'legacy-json', ?::jsonb, ?)
                    """.formatted(schema))) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, tenantId);
                statement.setObject(3, conversationId);
                statement.setString(4, state.summary());
                statement.setString(5, objectMapper.writeValueAsString(
                        Map.of("migration_run_id", runId.toString())
                ));
                statement.setTimestamp(6, Timestamp.from(updatedAt));
                statement.executeUpdate();
            }
        }
        return ImportOutcome.imported("CONVERSATION", conversationId);
    }

    private ImportOutcome importKnowledge(
            Connection connection, UUID tenantId, UUID runId, KnowledgeDocument document
    ) throws SQLException {
        String find = "SELECT id FROM %s.knowledge_documents WHERE tenant_id=? AND external_key=?"
                .formatted(schema);
        UUID existing = findUuid(connection, find, tenantId, document.id());
        if (existing != null) {
            return ImportOutcome.skipped("KNOWLEDGE_DOCUMENT", existing, "知识external_key已存在");
        }
        UUID documentId = UUID.nameUUIDFromBytes(
                (tenantId + "/" + document.id()).getBytes(StandardCharsets.UTF_8)
        );
        UUID versionId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO %s.knowledge_documents (
                    id, tenant_id, external_key, title, category, status,
                    source_type, active_version_no, metadata, migration_run_id
                ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', 'JSON_MIGRATION', 1, ?::jsonb, ?)
                """.formatted(schema))) {
            statement.setObject(1, documentId);
            statement.setObject(2, tenantId);
            statement.setString(3, document.id());
            statement.setString(4, document.title());
            statement.setString(5, document.category().name());
            statement.setString(6, objectMapper.writeValueAsString(
                    Map.of("migration_run_id", runId.toString())
            ));
            statement.setObject(7, runId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO %s.knowledge_document_versions (
                    id, tenant_id, document_id, version_no, checksum_sha256,
                    content_type, parser_version, ingestion_status, metadata,
                    content_text, keywords
                ) VALUES (?, ?, ?, 1, ?, 'text/plain; charset=UTF-8',
                          'legacy-json-v1', 'READY', ?::jsonb, ?, ?::jsonb)
                """.formatted(schema))) {
            statement.setObject(1, versionId);
            statement.setObject(2, tenantId);
            statement.setObject(3, documentId);
            statement.setString(4, sha256(document.content() + "\n" + document.keywords()));
            statement.setString(5, objectMapper.writeValueAsString(
                    Map.of("requires_vector_rebuild", true)
            ));
            statement.setString(6, document.content());
            statement.setString(7, objectMapper.writeValueAsString(document.keywords()));
            statement.executeUpdate();
        }
        return ImportOutcome.imported("KNOWLEDGE_DOCUMENT", documentId);
    }

    private ImportOutcome importIntent(
            Connection connection, UUID tenantId, UUID runId, IntentLearningSample sample
    ) throws SQLException {
        String normalized = sample.message().strip().toLowerCase(Locale.ROOT);
        UUID existing = findUuid(
                connection,
                "SELECT id FROM %s.intent_learning_samples WHERE tenant_id=? AND normalized_message=?"
                        .formatted(schema), tenantId, normalized
        );
        if (existing != null) {
            return ImportOutcome.skipped("INTENT_SAMPLE", existing, "规范化消息已存在");
        }
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO %s.intent_learning_samples (
                    id, tenant_id, message, normalized_message, correct_intent,
                    source, enabled, metadata, migration_run_id
                ) VALUES (?, ?, ?, ?, ?, 'JSON_MIGRATION', TRUE, ?::jsonb, ?)
                """.formatted(schema))) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setString(3, sample.message());
            statement.setString(4, normalized);
            statement.setString(5, sample.intent());
            statement.setString(6, objectMapper.writeValueAsString(
                    Map.of("migration_run_id", runId.toString())
            ));
            statement.setObject(7, runId);
            statement.executeUpdate();
        }
        return ImportOutcome.imported("INTENT_SAMPLE", id);
    }

    private ImportOutcome importAnswer(
            Connection connection, UUID tenantId, UUID runId, AnswerRecord answer
    ) throws SQLException {
        UUID existing = findUuid(
                connection,
                "SELECT id FROM %s.answer_records WHERE tenant_id=? AND response_id=?"
                        .formatted(schema), tenantId, answer.responseId()
        );
        if (existing != null) {
            return ImportOutcome.skipped("ANSWER_RECORD", existing, "response_id已存在");
        }
        Customer customer = findCustomer(connection, tenantId, answer.userId());
        if (customer == null) {
            customer = new Customer(insertCustomer(
                    connection, tenantId, runId, answer.userId(), Map.of()
            ), true);
        }
        UUID conversationId = findConversation(
                connection, tenantId, customer.id(), answer.conversationId()
        );
        if (conversationId == null) {
            conversationId = insertPlaceholderConversation(
                    connection, tenantId, runId, customer.id(), answer.conversationId()
            );
        }
        UUID id = parseUuidOrStable(answer.responseId(), tenantId + "/answer/");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO %s.answer_records (
                    id, tenant_id, conversation_id, response_id, response_text,
                    model_name, metadata, created_at, migration_run_id
                ) VALUES (?, ?, ?, ?, ?, 'legacy-json', ?::jsonb, ?, ?)
                """.formatted(schema))) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setObject(3, conversationId);
            statement.setString(4, answer.responseId());
            statement.setString(5, answer.content());
            statement.setString(6, objectMapper.writeValueAsString(
                    Map.of("legacy_user_id", answer.userId(), "migration_run_id", runId.toString())
            ));
            statement.setTimestamp(7, Timestamp.from(parseInstant(answer.createdAt())));
            statement.setObject(8, runId);
            statement.executeUpdate();
        }
        return ImportOutcome.imported("ANSWER_RECORD", id);
    }

    private ImportOutcome importFeedback(
            Connection connection, UUID tenantId, AnswerFeedback feedback
    ) throws SQLException {
        UUID answerId = findUuid(
                connection,
                "SELECT id FROM %s.answer_records WHERE tenant_id=? AND response_id=?"
                        .formatted(schema), tenantId, feedback.responseId()
        );
        if (answerId == null) {
            throw new IllegalArgumentException(
                    "反馈找不到对应回答：" + feedback.responseId()
            );
        }
        UUID existing = findUuid(
                connection,
                "SELECT id FROM %s.answer_feedback WHERE tenant_id=? AND answer_record_id=?"
                        .formatted(schema), tenantId, answerId
        );
        if (existing != null) {
            return ImportOutcome.skipped("ANSWER_FEEDBACK", existing, "回答已有反馈");
        }
        UUID id = parseUuidOrStable(feedback.id(), tenantId + "/feedback/");
        int rating = "positive".equalsIgnoreCase(feedback.rating()) ? 5 : 1;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO %s.answer_feedback (
                    id, tenant_id, answer_record_id, rating, comment,
                    labels, submitted_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, 'legacy-json', ?, ?)
                """.formatted(schema))) {
            Instant submitted = parseInstant(feedback.submittedAt());
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setObject(3, answerId);
            statement.setInt(4, rating);
            statement.setString(5, feedback.comment());
            statement.setString(6, objectMapper.writeValueAsString(
                    List.of("legacy_" + feedback.rating().toLowerCase(Locale.ROOT))
            ));
            statement.setTimestamp(7, Timestamp.from(submitted));
            statement.setTimestamp(8, Timestamp.from(submitted));
            statement.executeUpdate();
        }
        return ImportOutcome.imported("ANSWER_FEEDBACK", id);
    }

    private ImportOutcome importEvaluation(
            Connection connection, UUID tenantId, UUID runId,
            String sourceKey, EvalReport report
    ) throws SQLException {
        UUID id = UUID.nameUUIDFromBytes(
                (tenantId + "/" + sourceKey).getBytes(StandardCharsets.UTF_8)
        );
        UUID existing = findUuid(
                connection,
                "SELECT id FROM %s.evaluation_reports WHERE tenant_id=? AND id=?"
                        .formatted(schema), tenantId, id
        );
        if (existing != null) {
            return ImportOutcome.skipped("EVALUATION_REPORT", existing, "稳定报告ID已存在");
        }
        Map<String, Object> metrics = new LinkedHashMap<>(report.avgScores());
        metrics.put("total", report.total());
        metrics.put("passed", report.passed());
        metrics.put("pass_rate", report.passRate());
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO %s.evaluation_reports (
                    id, tenant_id, evaluation_type, dataset_version, model_name,
                    status, sample_count, metrics, item_results, metadata,
                    started_at, completed_at, created_at, migration_run_id
                ) VALUES (?, ?, 'END_TO_END', 'legacy-json', 'legacy-json',
                          'COMPLETED', ?, ?::jsonb, ?::jsonb, ?::jsonb,
                          ?, ?, ?, ?)
                """.formatted(schema))) {
            Instant timestamp = parseInstant(report.timestamp());
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setInt(3, report.total());
            statement.setString(4, objectMapper.writeValueAsString(metrics));
            statement.setString(5, objectMapper.writeValueAsString(report.results()));
            statement.setString(6, objectMapper.writeValueAsString(Map.of(
                    "regressions", report.regressions(),
                    "recommendations", report.recommendations(),
                    "migration_run_id", runId.toString()
            )));
            statement.setTimestamp(7, Timestamp.from(timestamp));
            statement.setTimestamp(8, Timestamp.from(timestamp));
            statement.setTimestamp(9, Timestamp.from(timestamp));
            statement.setObject(10, runId);
            statement.executeUpdate();
        }
        return ImportOutcome.imported("EVALUATION_REPORT", id);
    }

    private Customer findCustomer(Connection connection, UUID tenantId, String userId) throws SQLException {
        String sql = "SELECT id FROM %s.customer_identities WHERE tenant_id=? AND external_user_id=?"
                .formatted(schema);
        UUID id = findUuid(connection, sql, tenantId, normalizedUser(userId));
        return id == null ? null : new Customer(id, false);
    }

    private UUID insertCustomer(
            Connection connection, UUID tenantId, UUID runId,
            String userId, Map<String, String> profile
    ) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO %s.customer_identities (
                    id, tenant_id, external_user_id, profile, migration_run_id
                ) VALUES (?, ?, ?, ?::jsonb, ?)
                """.formatted(schema))) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setString(3, normalizedUser(userId));
            statement.setString(4, objectMapper.writeValueAsString(profile));
            statement.setObject(5, runId);
            statement.executeUpdate();
        }
        return id;
    }

    private UUID findConversation(
            Connection connection, UUID tenantId, UUID customerId, String conversationId
    ) throws SQLException {
        return findUuid(connection, """
                SELECT id FROM %s.conversations
                 WHERE tenant_id=? AND customer_id=? AND external_conversation_id=?
                """.formatted(schema), tenantId, customerId, conversationId.strip());
    }

    private UUID insertPlaceholderConversation(
            Connection connection, UUID tenantId, UUID runId,
            UUID customerId, String conversationId
    ) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO %s.conversations (
                    id, tenant_id, customer_id, external_conversation_id,
                    metadata, migration_run_id
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?)
                """.formatted(schema))) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setObject(3, customerId);
            statement.setString(4, conversationId.strip());
            statement.setString(5, objectMapper.writeValueAsString(
                    Map.of("legacy_feedback_placeholder", true)
            ));
            statement.setObject(6, runId);
            statement.executeUpdate();
        }
        return id;
    }

    private void insertMessages(
            Connection connection, UUID tenantId, UUID conversationId,
            List<MemoryMessage> messages
    ) throws SQLException {
        String sql = """
                INSERT INTO %s.conversation_messages (
                    id, tenant_id, conversation_id, sequence_no, role,
                    content, metadata, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """.formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            long sequence = 1;
            for (MemoryMessage message : messages) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, tenantId);
                statement.setObject(3, conversationId);
                statement.setLong(4, sequence++);
                statement.setString(5, message.role().name());
                statement.setString(6, message.content());
                statement.setString(7, "{\"legacy_json\":true}");
                statement.setTimestamp(8, Timestamp.from(message.createdAt()));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private boolean activeFingerprintExists(
            Connection connection, UUID tenantId, LegacyDatasetType type, String itemHash
    ) throws SQLException {
        String sql = """
                SELECT 1 FROM %s.legacy_migration_items i
                JOIN %s.legacy_migration_runs r ON r.id=i.run_id
                WHERE i.tenant_id=? AND i.dataset_type=? AND i.item_sha256=?
                  AND i.status='IMPORTED' AND r.status='COMPLETED'
                LIMIT 1
                """.formatted(schema, schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setString(2, type.name());
            statement.setString(3, itemHash);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private LegacyMigrationRun insertRun(
            UUID runId, UUID tenantId, ParsedLegacyDataset parsed
    ) {
        String sql = """
                INSERT INTO %s.legacy_migration_runs (
                    id, tenant_id, dataset_type, source_file, source_sha256,
                    source_size_bytes, status, discovered_items
                ) VALUES (?, ?, ?, ?, ?, ?, 'RUNNING', ?)
                """.formatted(schema);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, runId);
            statement.setObject(2, tenantId);
            statement.setString(3, parsed.type().name());
            statement.setString(4, parsed.sourcePath().toString());
            statement.setString(5, parsed.sourceSha256());
            statement.setLong(6, parsed.sourceSizeBytes());
            statement.setInt(7, parsed.items().size());
            statement.executeUpdate();
            connection.commit();
            return null;
        } catch (SQLException exception) {
            LegacyMigrationRun raced = findCompleted(
                    tenantId, parsed.type(), parsed.sourceSha256()
            );
            if (raced != null) {
                return raced;
            }
            throw new IllegalStateException("创建迁移批次失败", exception);
        }
    }

    private void insertItem(
            Connection connection, UUID tenantId, UUID runId,
            LegacyDatasetType type, LegacySourceItem item, ImportOutcome outcome
    ) throws SQLException {
        String sql = """
                INSERT INTO %s.legacy_migration_items (
                    run_id, tenant_id, dataset_type, source_key, item_sha256,
                    status, target_type, target_id, details
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """.formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, runId);
            statement.setObject(2, tenantId);
            statement.setString(3, type.name());
            statement.setString(4, item.sourceKey());
            statement.setString(5, item.itemSha256());
            statement.setString(6, outcome.imported() ? "IMPORTED" : "SKIPPED_EXISTING");
            statement.setString(7, outcome.targetType());
            statement.setObject(8, outcome.targetId());
            statement.setString(9, objectMapper.writeValueAsString(
                    Map.of("reason", outcome.reason())
            ));
            statement.executeUpdate();
        }
    }

    private void completeRun(
            Connection connection, UUID runId, int imported, int skipped
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE %s.legacy_migration_runs
                   SET status='COMPLETED', imported_items=?, skipped_items=?,
                       completed_at=CURRENT_TIMESTAMP, error_message=NULL
                 WHERE id=? AND status='RUNNING'
                """.formatted(schema))) {
            statement.setInt(1, imported);
            statement.setInt(2, skipped);
            statement.setObject(3, runId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("迁移批次不再是RUNNING状态");
            }
        }
    }

    private void markFailed(UUID runId, Exception failure) {
        String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
        if (message.length() > 2000) {
            message = message.substring(0, 2000);
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE %s.legacy_migration_runs
                        SET status='FAILED', error_message=?, completed_at=CURRENT_TIMESTAMP
                      WHERE id=? AND status='RUNNING'
                     """.formatted(schema))) {
            statement.setString(1, message);
            statement.setObject(2, runId);
            statement.executeUpdate();
            connection.commit();
        } catch (SQLException ignored) {

        }
    }

    private LegacyMigrationRun findCompleted(
            UUID tenantId, LegacyDatasetType type, String checksum
    ) {
        String sql = """
                SELECT * FROM %s.legacy_migration_runs
                 WHERE tenant_id=? AND dataset_type=? AND source_sha256=?
                   AND status='COMPLETED'
                 ORDER BY completed_at DESC LIMIT 1
                """.formatted(schema);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setString(2, type.name());
            statement.setString(3, checksum);
            try (ResultSet result = statement.executeQuery()) {
                LegacyMigrationRun run = result.next() ? mapRun(result) : null;
                connection.commit();
                return run;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询幂等迁移批次失败", exception);
        }
    }

    private LegacyMigrationRun mapRun(ResultSet result) throws SQLException {
        return new LegacyMigrationRun(
                result.getObject("id", UUID.class),
                result.getObject("tenant_id", UUID.class),
                LegacyDatasetType.valueOf(result.getString("dataset_type")),
                result.getString("source_file"), result.getString("source_sha256"),
                result.getLong("source_size_bytes"), result.getString("status"),
                result.getInt("discovered_items"), result.getInt("imported_items"),
                result.getInt("skipped_items"), result.getString("error_message"),
                instant(result, "started_at"), instant(result, "completed_at"),
                instant(result, "rolled_back_at")
        );
    }

    private boolean targetExists(
            Connection connection, UUID tenantId, String targetType, UUID targetId
    ) throws SQLException {
        if (targetType == null || targetId == null) {
            return false;
        }
        String table = switch (targetType) {
            case "CUSTOMER" -> "customer_identities";
            case "CONVERSATION" -> "conversations";
            case "KNOWLEDGE_DOCUMENT" -> "knowledge_documents";
            case "INTENT_SAMPLE" -> "intent_learning_samples";
            case "ANSWER_RECORD" -> "answer_records";
            case "ANSWER_FEEDBACK" -> "answer_feedback";
            case "EVALUATION_REPORT" -> "evaluation_reports";
            default -> null;
        };
        if (table == null) {
            return false;
        }
        String sql = "SELECT 1 FROM %s.%s WHERE tenant_id=? AND id=?"
                .formatted(schema, table);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, targetId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean targetMatches(
            Connection connection, UUID tenantId, String targetType,
            UUID targetId, LegacySourceItem source
    ) throws SQLException {
        return switch (targetType) {
            case "CUSTOMER" -> customerMatches(connection, tenantId, targetId, source);
            case "CONVERSATION" -> conversationMatches(connection, tenantId, targetId, source);
            case "KNOWLEDGE_DOCUMENT" -> knowledgeMatches(connection, tenantId, targetId, source);
            case "INTENT_SAMPLE" -> intentMatches(connection, tenantId, targetId, source);
            case "ANSWER_RECORD" -> answerMatches(connection, tenantId, targetId, source);
            case "ANSWER_FEEDBACK" -> feedbackMatches(connection, tenantId, targetId, source);
            case "EVALUATION_REPORT" -> evaluationMatches(connection, tenantId, targetId, source);
            default -> false;
        };
    }

    private boolean customerMatches(
            Connection connection, UUID tenantId, UUID targetId, LegacySourceItem source
    ) throws SQLException {
        if (!(source.payload() instanceof LegacyUserProfile profile)) {
            return true;
        }
        String sql = "SELECT external_user_id, profile::text FROM %s.customer_identities WHERE tenant_id=? AND id=?"
                .formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, targetId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return false;
                }
                Map<?, ?> stored = objectMapper.readValue(result.getString("profile"), Map.class);
                return normalizedUser(profile.userId()).equals(result.getString("external_user_id"))
                        && stored.equals(profile.profile());
            }
        }
    }

    private boolean conversationMatches(
            Connection connection, UUID tenantId, UUID targetId, LegacySourceItem source
    ) throws SQLException {
        if (!(source.payload() instanceof MemoryConversationState state)) {
            return true;
        }
        String sql = """
                SELECT u.external_user_id, c.external_conversation_id,
                       COALESCE((SELECT s.summary FROM %s.conversation_summaries s
                                 WHERE s.tenant_id=c.tenant_id AND s.conversation_id=c.id
                                 ORDER BY s.version_no DESC LIMIT 1), '') AS summary
                  FROM %s.conversations c
                  JOIN %s.customer_identities u
                    ON u.tenant_id=c.tenant_id AND u.id=c.customer_id
                 WHERE c.tenant_id=? AND c.id=?
                """.formatted(schema, schema, schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, targetId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || !normalizedUser(state.userId()).equals(result.getString("external_user_id"))
                        || !state.conversationId().equals(result.getString("external_conversation_id"))
                        || !state.summary().equals(result.getString("summary"))) {
                    return false;
                }
            }
        }
        String messagesSql = """
                SELECT role, content, created_at FROM %s.conversation_messages
                 WHERE tenant_id=? AND conversation_id=? ORDER BY sequence_no
                """.formatted(schema);
        List<MemoryMessage> stored = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(messagesSql)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, targetId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    stored.add(new MemoryMessage(
                            com.supportforge.memory.MemoryRole.valueOf(result.getString("role")),
                            result.getString("content"), result.getTimestamp("created_at").toInstant()
                    ));
                }
            }
        }
        return stored.equals(state.recentMessages());
    }

    private boolean knowledgeMatches(
            Connection connection, UUID tenantId, UUID targetId, LegacySourceItem source
    ) throws SQLException {
        if (!(source.payload() instanceof KnowledgeDocument document)) {
            return false;
        }
        String sql = """
                SELECT d.external_key, d.title, d.category, v.content_text, v.keywords::text
                  FROM %s.knowledge_documents d
                  JOIN %s.knowledge_document_versions v
                    ON v.tenant_id=d.tenant_id AND v.document_id=d.id
                   AND v.version_no=d.active_version_no
                 WHERE d.tenant_id=? AND d.id=?
                """.formatted(schema, schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, targetId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return false;
                }
                String[] keywords = objectMapper.readValue(result.getString(5), String[].class);
                return document.id().equals(result.getString("external_key"))
                        && document.title().equals(result.getString("title"))
                        && document.category().name().equals(result.getString("category"))
                        && document.content().equals(result.getString("content_text"))
                        && document.keywords().equals(List.of(keywords));
            }
        }
    }

    private boolean intentMatches(
            Connection connection, UUID tenantId, UUID targetId, LegacySourceItem source
    ) throws SQLException {
        if (!(source.payload() instanceof IntentLearningSample sample)) {
            return false;
        }
        String sql = "SELECT message, correct_intent FROM %s.intent_learning_samples WHERE tenant_id=? AND id=?"
                .formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, targetId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && sample.message().equals(result.getString("message"))
                        && sample.intent().equals(result.getString("correct_intent"));
            }
        }
    }

    private boolean answerMatches(
            Connection connection, UUID tenantId, UUID targetId, LegacySourceItem source
    ) throws SQLException {
        if (!(source.payload() instanceof AnswerRecord answer)) {
            return false;
        }
        String sql = """
                SELECT a.response_id, a.response_text, c.external_conversation_id,
                       u.external_user_id, a.created_at
                  FROM %s.answer_records a
                  JOIN %s.conversations c ON c.tenant_id=a.tenant_id AND c.id=a.conversation_id
                  JOIN %s.customer_identities u ON u.tenant_id=c.tenant_id AND u.id=c.customer_id
                 WHERE a.tenant_id=? AND a.id=?
                """.formatted(schema, schema, schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, targetId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        && answer.responseId().equals(result.getString("response_id"))
                        && answer.content().equals(result.getString("response_text"))
                        && answer.conversationId().equals(result.getString("external_conversation_id"))
                        && normalizedUser(answer.userId()).equals(result.getString("external_user_id"))
                        && parseInstant(answer.createdAt()).equals(result.getTimestamp("created_at").toInstant());
            }
        }
    }

    private boolean feedbackMatches(
            Connection connection, UUID tenantId, UUID targetId, LegacySourceItem source
    ) throws SQLException {
        if (!(source.payload() instanceof AnswerFeedback feedback)) {
            return false;
        }
        String sql = """
                SELECT f.rating, f.comment, a.response_id, f.created_at
                  FROM %s.answer_feedback f
                  JOIN %s.answer_records a
                    ON a.tenant_id=f.tenant_id AND a.id=f.answer_record_id
                 WHERE f.tenant_id=? AND f.id=?
                """.formatted(schema, schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, targetId);
            try (ResultSet result = statement.executeQuery()) {
                int expectedRating = "positive".equalsIgnoreCase(feedback.rating()) ? 5 : 1;
                return result.next()
                        && expectedRating == result.getInt("rating")
                        && java.util.Objects.equals(feedback.comment(), result.getString("comment"))
                        && feedback.responseId().equals(result.getString("response_id"))
                        && parseInstant(feedback.submittedAt()).equals(result.getTimestamp("created_at").toInstant());
            }
        }
    }

    private boolean evaluationMatches(
            Connection connection, UUID tenantId, UUID targetId, LegacySourceItem source
    ) throws SQLException {
        if (!(source.payload() instanceof EvalReport report)) {
            return false;
        }
        String sql = "SELECT sample_count, metrics::text, item_results::text FROM %s.evaluation_reports WHERE tenant_id=? AND id=?"
                .formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, targetId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || report.total() != result.getInt("sample_count")) {
                    return false;
                }
                Map<?, ?> metrics = objectMapper.readValue(result.getString("metrics"), Map.class);
                Object passRate = metrics.get("pass_rate");
                Object passed = metrics.get("passed");
                Object storedItems = objectMapper.readValue(result.getString("item_results"), Object.class);
                Object sourceItems = objectMapper.readValue(
                        objectMapper.writeValueAsString(report.results()), Object.class
                );
                return passRate instanceof Number rate
                        && Math.abs(rate.doubleValue() - report.passRate()) < 1e-12
                        && passed instanceof Number count
                        && count.intValue() == report.passed()
                        && storedItems.equals(sourceItems);
            }
        }
    }

    private UUID findUuid(
            Connection connection, String sql, Object... parameters
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getObject(1, UUID.class) : null;
            }
        }
    }

    private static String normalizedUser(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId.strip();
    }

    private static UUID parseUuidOrStable(String value, String prefix) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            return UUID.nameUUIDFromBytes(
                    (prefix + value).getBytes(StandardCharsets.UTF_8)
            );
        }
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? Instant.now() : Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("旧JSON时间不是ISO-8601：" + value, exception);
        }
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM缺少SHA-256", exception);
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record Customer(UUID id, boolean createdByRun) { }

    private record ImportOutcome(
            boolean imported, String targetType, UUID targetId, String reason
    ) {
        private static ImportOutcome imported(String type, UUID id) {
            return new ImportOutcome(true, type, id, "created");
        }

        private static ImportOutcome skipped(String type, UUID id, String reason) {
            return new ImportOutcome(false, type, id, reason);
        }
    }
}
