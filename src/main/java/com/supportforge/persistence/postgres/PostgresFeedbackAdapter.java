package com.supportforge.persistence.postgres;

import com.supportforge.config.DatabaseProperties;
import com.supportforge.feedback.AnswerFeedback;
import com.supportforge.feedback.AnswerRecord;
import com.supportforge.feedback.FeedbackSnapshot;
import com.supportforge.persistence.port.FeedbackPersistencePort;
import com.supportforge.security.TenantContextProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** PostgreSQL回答/反馈Adapter；每次调用按TenantContext隔离，不共享跨租户快照。 */
@Component
@DependsOn("supportForgeFlyway")
@ConditionalOnProperty(
        prefix = "supportforge.persistence", name = "feedback", havingValue = "postgres"
)
public class PostgresFeedbackAdapter implements FeedbackPersistencePort {
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final TenantContextProvider tenants;
    private final String schema;
    private final int maxRecords;

    public PostgresFeedbackAdapter(
            DataSource dataSource, ObjectMapper objectMapper,
            TenantContextProvider tenants, DatabaseProperties database,
            @org.springframework.beans.factory.annotation.Value("${supportforge.feedback.max-records:1000}")
            int maxRecords
    ) {
        if (!database.schema().matches("[A-Za-z_][A-Za-z0-9_]*") || maxRecords < 1) {
            throw new IllegalArgumentException("PostgreSQL反馈配置不合法");
        }
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.tenants = tenants;
        this.schema = database.schema();
        this.maxRecords = maxRecords;
    }

    @Override
    public String scopeKey() {
        return tenants.tenantId().toString();
    }

    @Override
    public FeedbackSnapshot load() {
        UUID tenantId = tenants.tenantId();
        List<AnswerRecord> answers = new ArrayList<>();
        List<AnswerFeedback> feedbacks = new ArrayList<>();
        String answersSql = """
                SELECT a.id, a.response_id, a.response_text, a.created_at,
                       c.external_conversation_id, u.external_user_id
                  FROM (
                       SELECT * FROM %s.answer_records
                        WHERE tenant_id=? ORDER BY created_at DESC, id DESC LIMIT ?
                  ) a
                  JOIN %s.conversations c ON c.tenant_id=a.tenant_id AND c.id=a.conversation_id
                  JOIN %s.customer_identities u ON u.tenant_id=c.tenant_id AND u.id=c.customer_id
                 ORDER BY a.created_at, a.id
                """.formatted(schema, schema, schema);
        String feedbackSql = """
                SELECT f.id, a.response_id, c.external_conversation_id,
                       f.rating, f.comment, f.updated_at
                  FROM %s.answer_feedback f
                  JOIN %s.answer_records a ON a.tenant_id=f.tenant_id AND a.id=f.answer_record_id
                  JOIN %s.conversations c ON c.tenant_id=a.tenant_id AND c.id=a.conversation_id
                 WHERE f.tenant_id=?
                 ORDER BY f.updated_at, f.id
                """.formatted(schema, schema, schema);
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(answersSql)) {
                statement.setObject(1, tenantId);
                statement.setInt(2, maxRecords);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        answers.add(new AnswerRecord(
                                result.getString("response_id"),
                                result.getString("external_conversation_id"),
                                result.getString("external_user_id"),
                                result.getString("response_text"),
                                result.getTimestamp("created_at").toInstant().toString()
                        ));
                    }
                }
            }
            Set<String> retained = answers.stream().map(AnswerRecord::responseId)
                    .collect(Collectors.toSet());
            try (PreparedStatement statement = connection.prepareStatement(feedbackSql)) {
                statement.setObject(1, tenantId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        String responseId = result.getString("response_id");
                        if (retained.contains(responseId)) {
                            feedbacks.add(new AnswerFeedback(
                                    result.getObject("id", UUID.class).toString(), responseId,
                                    result.getString("external_conversation_id"),
                                    result.getInt("rating") >= 4 ? "positive" : "negative",
                                    result.getString("comment"),
                                    result.getTimestamp("updated_at").toInstant().toString()
                            ));
                        }
                    }
                }
            }
            connection.commit();
            return normalize(new FeedbackSnapshot(answers, feedbacks));
        } catch (SQLException exception) {
            throw new IllegalStateException("读取PostgreSQL反馈失败", exception);
        }
    }

    @Override
    public void save(FeedbackSnapshot snapshot) {
        UUID tenantId = tenants.tenantId();
        FeedbackSnapshot normalized = normalize(snapshot);
        try (Connection connection = dataSource.getConnection()) {
            try {
                Map<String, UUID> answerIds = new LinkedHashMap<>();
                for (AnswerRecord answer : normalized.answers()) {
                    UUID customerId = ensureCustomer(connection, tenantId, answer.userId());
                    UUID conversationId = ensureConversation(
                            connection, tenantId, customerId, answer.conversationId()
                    );
                    UUID answerId = upsertAnswer(
                            connection, tenantId, conversationId, answer
                    );
                    answerIds.put(answer.responseId(), answerId);
                }
                for (AnswerFeedback feedback : normalized.feedbacks()) {
                    UUID answerId = answerIds.get(feedback.responseId());
                    if (answerId == null) {
                        answerId = findAnswer(connection, tenantId, feedback.responseId());
                    }
                    if (answerId == null) {
                        throw new IllegalArgumentException("反馈找不到回答：" + feedback.responseId());
                    }
                    upsertFeedback(connection, tenantId, answerId, feedback);
                }
                prune(connection, tenantId);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("保存PostgreSQL反馈失败", exception);
        }
    }

    @Override
    public void saveAnswer(FeedbackSnapshot snapshot, AnswerRecord answer) {
        UUID tenantId = tenants.tenantId();
        try (Connection connection = dataSource.getConnection()) {
            try {
                UUID customerId = ensureCustomer(connection, tenantId, answer.userId());
                UUID conversationId = ensureConversation(
                        connection, tenantId, customerId, answer.conversationId()
                );
                upsertAnswer(connection, tenantId, conversationId, answer);
                prune(connection, tenantId);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("增量保存PostgreSQL回答失败", exception);
        }
    }

    @Override
    public void saveFeedback(FeedbackSnapshot snapshot, AnswerFeedback feedback) {
        UUID tenantId = tenants.tenantId();
        try (Connection connection = dataSource.getConnection()) {
            try {
                UUID answerId = findAnswer(connection, tenantId, feedback.responseId());
                if (answerId == null) {
                    throw new IllegalArgumentException("反馈找不到回答：" + feedback.responseId());
                }
                upsertFeedback(connection, tenantId, answerId, feedback);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("增量保存PostgreSQL反馈失败", exception);
        }
    }

    @Override
    public boolean supportsConcurrentIncrementalWrites() {
        return true;
    }

    @Override
    public FeedbackSnapshot normalize(FeedbackSnapshot snapshot) {
        if (snapshot == null) {
            return FeedbackSnapshot.empty();
        }
        LinkedHashMap<String, AnswerRecord> answers = new LinkedHashMap<>();
        snapshot.answers().forEach(answer -> {
            answers.remove(answer.responseId());
            answers.put(answer.responseId(), answer);
        });
        List<AnswerRecord> all = List.copyOf(answers.values());
        List<AnswerRecord> retained = List.copyOf(all.subList(
                Math.max(0, all.size() - maxRecords), all.size()
        ));
        Set<String> ids = retained.stream().map(AnswerRecord::responseId)
                .collect(Collectors.toSet());
        LinkedHashMap<String, AnswerFeedback> feedbacks = new LinkedHashMap<>();
        snapshot.feedbacks().stream().filter(item -> ids.contains(item.responseId()))
                .forEach(item -> {
                    feedbacks.remove(item.responseId());
                    feedbacks.put(item.responseId(), item);
                });
        return new FeedbackSnapshot(retained, List.copyOf(feedbacks.values()));
    }

    private UUID ensureCustomer(Connection connection, UUID tenantId, String userId) throws SQLException {
        String normalized = userId == null || userId.isBlank() ? "anonymous" : userId.strip();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO %s.customer_identities (id, tenant_id, external_user_id, profile)
                VALUES (?, ?, ?, '{}'::jsonb)
                ON CONFLICT (tenant_id, external_user_id)
                DO UPDATE SET updated_at=CURRENT_TIMESTAMP RETURNING id
                """.formatted(schema))) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, tenantId);
            statement.setString(3, normalized);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private UUID ensureConversation(
            Connection connection, UUID tenantId, UUID customerId, String externalId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO %s.conversations (
                    id, tenant_id, customer_id, external_conversation_id, metadata
                ) VALUES (?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (tenant_id, customer_id, external_conversation_id)
                DO UPDATE SET updated_at=CURRENT_TIMESTAMP RETURNING id
                """.formatted(schema))) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, tenantId);
            statement.setObject(3, customerId);
            statement.setString(4, externalId.strip());
            statement.setString(5, "{\"created_by\":\"feedback-adapter\"}");
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private UUID upsertAnswer(
            Connection connection, UUID tenantId, UUID conversationId, AnswerRecord answer
    ) throws SQLException {
        UUID id = uuidOrStable(answer.responseId(), tenantId + "/answer/");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO %s.answer_records (
                    id, tenant_id, conversation_id, response_id, response_text,
                    model_name, metadata, created_at
                ) VALUES (?, ?, ?, ?, ?, 'supportforge', ?::jsonb, ?)
                ON CONFLICT (tenant_id, response_id) DO UPDATE SET
                    conversation_id=EXCLUDED.conversation_id,
                    response_text=EXCLUDED.response_text,
                    metadata=EXCLUDED.metadata
                RETURNING id
                """.formatted(schema))) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setObject(3, conversationId);
            statement.setString(4, answer.responseId());
            statement.setString(5, answer.content());
            statement.setString(6, objectMapper.writeValueAsString(
                    Map.of("user_id", answer.userId())
            ));
            statement.setTimestamp(7, Timestamp.from(Instant.parse(answer.createdAt())));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private UUID findAnswer(Connection connection, UUID tenantId, String responseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM %s.answer_records WHERE tenant_id=? AND response_id=?"
                        .formatted(schema))) {
            statement.setObject(1, tenantId);
            statement.setString(2, responseId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getObject(1, UUID.class) : null;
            }
        }
    }

    private void upsertFeedback(
            Connection connection, UUID tenantId, UUID answerId, AnswerFeedback feedback
    ) throws SQLException {
        UUID id = uuidOrStable(feedback.id(), tenantId + "/feedback/");
        Instant submitted = Instant.parse(feedback.submittedAt());
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO %s.answer_feedback (
                    id, tenant_id, answer_record_id, rating, comment,
                    labels, submitted_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, 'api', ?, ?)
                ON CONFLICT (tenant_id, answer_record_id) DO UPDATE SET
                    rating=EXCLUDED.rating, comment=EXCLUDED.comment,
                    labels=EXCLUDED.labels, updated_at=EXCLUDED.updated_at
                """.formatted(schema))) {
            statement.setObject(1, id);
            statement.setObject(2, tenantId);
            statement.setObject(3, answerId);
            statement.setInt(4, "positive".equals(feedback.rating()) ? 5 : 1);
            statement.setString(5, feedback.comment());
            statement.setString(6, objectMapper.writeValueAsString(List.of(feedback.rating())));
            statement.setTimestamp(7, Timestamp.from(submitted));
            statement.setTimestamp(8, Timestamp.from(submitted));
            statement.executeUpdate();
        }
    }

    private void prune(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM %s.answer_records
                 WHERE tenant_id=? AND id IN (
                       SELECT id FROM %s.answer_records WHERE tenant_id=?
                        ORDER BY created_at DESC, id DESC OFFSET ?)
                """.formatted(schema, schema))) {
            statement.setObject(1, tenantId);
            statement.setObject(2, tenantId);
            statement.setInt(3, maxRecords);
            statement.executeUpdate();
        }
    }

    private static UUID uuidOrStable(String value, String prefix) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            return UUID.nameUUIDFromBytes((prefix + value).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException failure) {
            original.addSuppressed(failure);
        }
    }
}
