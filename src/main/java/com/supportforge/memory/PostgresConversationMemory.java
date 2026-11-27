package com.supportforge.memory;

import com.supportforge.config.DatabaseProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


@Component
@ConditionalOnProperty(
        prefix = "supportforge.persistence",
        name = "memory",
        havingValue = "postgres"
)
public class PostgresConversationMemory implements ConversationMemory {

    private static final TypeReference<Map<String, String>> STRING_MAP =
            new TypeReference<>() { };

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final ConversationSummarizer summarizer;
    private final RelevantHistoryRetriever historyRetriever;
    private final EpisodicMemoryRetriever episodicMemoryRetriever;
    private final UserProfileExtractor profileExtractor;
    private final UUID tenantId;
    private final String schema;
    private final int maxConversations;
    private final int maxMessages;
    private final int compressionBatchSize;

    public PostgresConversationMemory(
            DataSource dataSource,
            ObjectMapper objectMapper,
            ConversationSummarizer summarizer,
            RelevantHistoryRetriever historyRetriever,
            EpisodicMemoryRetriever episodicMemoryRetriever,
            UserProfileExtractor profileExtractor,
            DatabaseProperties database,
            @Value("${supportforge.persistence.default-tenant-id:00000000-0000-0000-0000-000000000001}")
            String defaultTenantId,
            @Value("${supportforge.memory.max-conversations:1000}") int maxConversations,
            @Value("${supportforge.memory.max-messages:12}") int maxMessages,
            @Value("${supportforge.memory.compression-batch-size:4}") int compressionBatchSize
    ) {
        if (!database.schema().matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("数据库schema名称不安全");
        }
        if (maxConversations < 1 || maxMessages < 1
                || compressionBatchSize < 1 || compressionBatchSize > maxMessages) {
            throw new IllegalArgumentException("会话记忆容量配置不合法");
        }
        try {
            this.tenantId = UUID.fromString(defaultTenantId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("默认租户编号必须是UUID：" + defaultTenantId, exception);
        }
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.summarizer = summarizer;
        this.historyRetriever = historyRetriever;
        this.episodicMemoryRetriever = episodicMemoryRetriever;
        this.profileExtractor = profileExtractor;
        this.schema = database.schema();
        this.maxConversations = maxConversations;
        this.maxMessages = maxMessages;
        this.compressionBatchSize = compressionBatchSize;
    }

    @Override
    public void append(String userId, String conversationId, MemoryMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("记忆消息不能为空");
        }
        ConversationKey key = new ConversationKey(userId, conversationId);
        try (Connection connection = dataSource.getConnection()) {
            try {
                Customer customer = upsertCustomer(connection, key.userId());
                UUID conversation = upsertAndLockConversation(
                        connection, customer.id(), key.conversationId()
                );
                Map<String, String> profile = customer.profile();
                if (message.role() == MemoryRole.USER) {
                    profile = profileExtractor.update(profile, message.content());
                    updateCustomerProfile(connection, customer.id(), profile);
                }
                long sequence = nextSequence(connection, conversation);
                insertMessage(connection, conversation, sequence, message);
                SummaryState summary = latestSummary(connection, conversation);
                List<SequencedMessage> unsummarized = loadMessagesAfter(
                        connection, conversation, summary.sourceMessageCount()
                );
                if (unsummarized.size() > maxMessages) {
                    int count = Math.min(compressionBatchSize, unsummarized.size());
                    List<MemoryMessage> batch = unsummarized.subList(0, count).stream()
                            .map(SequencedMessage::message)
                            .toList();
                    String updated = summarizer.summarize(summary.text(), batch);
                    long coveredThrough = unsummarized.get(count - 1).sequence();
                    insertSummary(
                            connection, conversation, summary.version() + 1,
                            Math.toIntExact(coveredThrough), updated
                    );
                    insertEpisode(
                            connection, customer.id(), conversation,
                            summary.version() + 1,
                            summary.sourceMessageCount() + 1,
                            coveredThrough, updated
                    );
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("保存PostgreSQL会话消息失败", exception);
        }
    }

    @Override
    public MemoryContext getContext(String userId, String conversationId, String query) {
        ConversationKey key = new ConversationKey(userId, conversationId);
        try (Connection connection = dataSource.getConnection()) {
            Customer customer = findCustomer(connection, key.userId());
            if (customer == null) {
                return new MemoryContext(
                        List.of(), "", List.of(),
                        profileExtractor.update(Map.of(), query)
                );
            }
            MemorySnapshot snapshot = loadUserSnapshot(connection, customer);
            MemoryConversationState current = snapshot.conversations().stream()
                    .filter(state -> state.conversationId().equals(key.conversationId()))
                    .findFirst()
                    .orElse(null);
            List<String> history = historyRetriever.retrieve(
                    snapshot, key.userId(), key.conversationId(), query
            );
            List<String> episodicHistory = episodicMemoryRetriever.retrieve(
                    loadEpisodes(connection, customer.id()), key.conversationId(), query
            );
            List<String> combinedHistory = new ArrayList<>(history);
            episodicHistory.stream()
                    .filter(item -> !combinedHistory.contains(item))
                    .forEach(combinedHistory::add);
            Map<String, String> effectiveProfile = profileExtractor.update(
                    customer.profile(), query
            );
            if (current == null) {
                return new MemoryContext(List.of(), "", combinedHistory, effectiveProfile);
            }
            return new MemoryContext(
                    current.recentMessages(), current.summary(), combinedHistory, effectiveProfile
            );
        } catch (SQLException exception) {
            throw new IllegalStateException("读取PostgreSQL会话记忆失败", exception);
        }
    }

    @Override
    public void clear(String userId, String conversationId) {
        ConversationKey key = new ConversationKey(userId, conversationId);
        String sql = """
                SELECT c.id FROM %s.conversations c
                  JOIN %s.customer_identities u ON u.id = c.customer_id AND u.tenant_id = c.tenant_id
                 WHERE c.tenant_id = ? AND c.customer_id = u.id
                   AND u.tenant_id = c.tenant_id
                   AND u.external_user_id = ?
                   AND c.external_conversation_id = ?
                 FOR UPDATE OF c
                """.formatted(schema, schema);
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, tenantId());
                statement.setString(2, key.userId());
                statement.setString(3, key.conversationId());
                UUID conversation;
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        connection.commit();
                        return;
                    }
                    conversation = rows.getObject("id", UUID.class);
                }


                clearMemoryRows(connection, conversation);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("清除PostgreSQL会话失败", exception);
        }
    }

    private void clearMemoryRows(Connection connection, UUID conversation) throws SQLException {
        String detach = """
                UPDATE %s.answer_records
                   SET request_message_id = NULL, response_message_id = NULL
                 WHERE tenant_id = ? AND conversation_id = ?
                """.formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(detach)) {
            statement.setObject(1, tenantId());
            statement.setObject(2, conversation);
            statement.executeUpdate();
        }

        for (String table : List.of("memory_episodes", "conversation_summaries", "conversation_messages")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + schema + "." + table + " WHERE tenant_id = ? AND conversation_id = ?")) {
                statement.setObject(1, tenantId());
                statement.setObject(2, conversation);
                statement.executeUpdate();
            }
        }
    }

    private Customer upsertCustomer(Connection connection, String userId) throws SQLException {
        String sql = """
                INSERT INTO %s.customer_identities (
                    id, tenant_id, external_user_id, profile
                ) VALUES (?, ?, ?, '{}'::jsonb)
                ON CONFLICT (tenant_id, external_user_id)
                DO UPDATE SET updated_at = CURRENT_TIMESTAMP
                RETURNING id, profile::text
                """.formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, tenantId());
            statement.setString(3, userId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return new Customer(
                        result.getObject("id", UUID.class), userId,
                        decodeProfile(result.getString("profile"))
                );
            }
        }
    }

    private Customer findCustomer(Connection connection, String userId) throws SQLException {
        String sql = "SELECT id, profile::text FROM %s.customer_identities WHERE tenant_id=? AND external_user_id=?"
                .formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId());
            statement.setString(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new Customer(
                        result.getObject("id", UUID.class), userId,
                        decodeProfile(result.getString("profile"))
                );
            }
        }
    }

    private UUID upsertAndLockConversation(
            Connection connection, UUID customerId, String conversationId
    ) throws SQLException {
        String sql = """
                INSERT INTO %s.conversations (
                    id, tenant_id, customer_id, external_conversation_id,
                    last_message_at, updated_at
                ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id, customer_id, external_conversation_id)
                DO UPDATE SET last_message_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP
                RETURNING id
                """.formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, tenantId());
            statement.setObject(3, customerId);
            statement.setString(4, conversationId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getObject("id", UUID.class);
            }
        }
    }

    private void updateCustomerProfile(
            Connection connection, UUID customerId, Map<String, String> profile
    ) throws SQLException {
        String sql = "UPDATE %s.customer_identities SET profile=?::jsonb, updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?"
                .formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, objectMapper.writeValueAsString(profile));
            statement.setObject(2, tenantId());
            statement.setObject(3, customerId);
            statement.executeUpdate();
        }
    }

    private long nextSequence(Connection connection, UUID conversationId) throws SQLException {
        String sql = "SELECT COALESCE(MAX(sequence_no),0)+1 FROM %s.conversation_messages WHERE tenant_id=? AND conversation_id=?"
                .formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId());
            statement.setObject(2, conversationId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void insertMessage(
            Connection connection, UUID conversationId, long sequence, MemoryMessage message
    ) throws SQLException {
        String sql = """
                INSERT INTO %s.conversation_messages (
                    id, tenant_id, conversation_id, sequence_no, role, content, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, tenantId());
            statement.setObject(3, conversationId);
            statement.setLong(4, sequence);
            statement.setString(5, message.role().name());
            statement.setString(6, message.content());
            statement.setTimestamp(7, Timestamp.from(message.createdAt()));
            statement.executeUpdate();
        }
    }

    private SummaryState latestSummary(Connection connection, UUID conversationId) throws SQLException {
        String sql = """
                SELECT version_no, summary, source_message_count
                  FROM %s.conversation_summaries
                 WHERE tenant_id=? AND conversation_id=?
                 ORDER BY version_no DESC LIMIT 1
                """.formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId());
            statement.setObject(2, conversationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? new SummaryState(
                                result.getInt("version_no"), result.getString("summary"),
                                result.getInt("source_message_count")
                        )
                        : new SummaryState(0, "", 0);
            }
        }
    }

    private List<SequencedMessage> loadMessagesAfter(
            Connection connection, UUID conversationId, long sequence
    ) throws SQLException {
        String sql = """
                SELECT sequence_no, role, content, created_at
                  FROM %s.conversation_messages
                 WHERE tenant_id=? AND conversation_id=? AND sequence_no>?
                 ORDER BY sequence_no
                """.formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId());
            statement.setObject(2, conversationId);
            statement.setLong(3, sequence);
            try (ResultSet result = statement.executeQuery()) {
                List<SequencedMessage> messages = new ArrayList<>();
                while (result.next()) {
                    messages.add(new SequencedMessage(
                            result.getLong("sequence_no"),
                            new MemoryMessage(
                                    MemoryRole.valueOf(result.getString("role")),
                                    result.getString("content"),
                                    result.getTimestamp("created_at").toInstant()
                            )
                    ));
                }
                return messages;
            }
        }
    }

    private void insertSummary(
            Connection connection, UUID conversationId, int version,
            int sourceMessageCount, String summary
    ) throws SQLException {
        String sql = """
                INSERT INTO %s.conversation_summaries (
                    id, tenant_id, conversation_id, version_no,
                    summary, source_message_count, model_name
                ) VALUES (?, ?, ?, ?, ?, ?, 'supportforge-memory')
                """.formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, tenantId());
            statement.setObject(3, conversationId);
            statement.setInt(4, version);
            statement.setString(5, summary);
            statement.setInt(6, sourceMessageCount);
            statement.executeUpdate();
        }
    }

    private void insertEpisode(
            Connection connection, UUID customerId, UUID conversationId,
            int summaryVersion, long sourceFrom, long sourceTo, String content
    ) throws SQLException {
        String sql = """
                INSERT INTO %s.memory_episodes (
                    id, tenant_id, customer_id, conversation_id,
                    summary_version, content, importance,
                    source_from_sequence, source_to_sequence
                ) VALUES (?, ?, ?, ?, ?, ?, 1.0, ?, ?)
                ON CONFLICT (tenant_id, conversation_id, summary_version) DO NOTHING
                """.formatted(schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, tenantId());
            statement.setObject(3, customerId);
            statement.setObject(4, conversationId);
            statement.setInt(5, summaryVersion);
            statement.setString(6, content);
            statement.setLong(7, sourceFrom);
            statement.setLong(8, sourceTo);
            statement.executeUpdate();
        }
    }

    private List<MemoryEpisode> loadEpisodes(
            Connection connection, UUID customerId
    ) throws SQLException {
        String sql = """
                SELECT c.external_conversation_id, e.summary_version,
                       e.content, e.importance, e.created_at
                  FROM %s.memory_episodes e
                  JOIN %s.conversations c
                    ON c.tenant_id=e.tenant_id AND c.id=e.conversation_id
                 WHERE e.tenant_id=? AND e.customer_id=?
                 ORDER BY e.created_at DESC LIMIT 100
                """.formatted(schema, schema);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId());
            statement.setObject(2, customerId);
            try (ResultSet result = statement.executeQuery()) {
                List<MemoryEpisode> episodes = new ArrayList<>();
                while (result.next()) {
                    episodes.add(new MemoryEpisode(
                            result.getString("external_conversation_id"),
                            result.getInt("summary_version"),
                            result.getString("content"),
                            result.getDouble("importance"),
                            result.getTimestamp("created_at").toInstant()
                    ));
                }
                return episodes;
            }
        }
    }

    private MemorySnapshot loadUserSnapshot(Connection connection, Customer customer) throws SQLException {
        String sql = """
                SELECT id, external_conversation_id, updated_at
                  FROM %s.conversations
                 WHERE tenant_id=? AND customer_id=?
                 ORDER BY last_message_at DESC LIMIT ?
                """.formatted(schema);
        List<MemoryConversationState> states = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId());
            statement.setObject(2, customer.id());
            statement.setInt(3, maxConversations);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID id = result.getObject("id", UUID.class);
                    SummaryState summary = latestSummary(connection, id);
                    List<MemoryMessage> recent = loadMessagesAfter(
                            connection, id, summary.sourceMessageCount()
                    ).stream().map(SequencedMessage::message).toList();
                    states.add(new MemoryConversationState(
                            customer.externalUserId(),
                            result.getString("external_conversation_id"),
                            recent, summary.text(), customer.profile(),
                            result.getTimestamp("updated_at").toInstant()
                    ));
                }
            }
        }
        Collections.reverse(states);
        return new MemorySnapshot(states, Map.of(customer.externalUserId(), customer.profile()));
    }

    private Map<String, String> decodeProfile(String json) {
        try {
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            return new LinkedHashMap<>(objectMapper.readValue(json, STRING_MAP));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("用户画像JSON损坏", exception);
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record Customer(UUID id, String externalUserId, Map<String, String> profile) { }

    private record SummaryState(int version, String text, int sourceMessageCount) { }

    private record SequencedMessage(long sequence, MemoryMessage message) { }


    private UUID tenantId() {
        return com.supportforge.security.TenantContext.current()
                .map(com.supportforge.security.RequestIdentity::tenantId)
                .orElse(tenantId);
    }
}
