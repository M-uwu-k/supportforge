package com.supportforge.migration;

import com.supportforge.evaluation.EvalReport;
import com.supportforge.feedback.AnswerFeedback;
import com.supportforge.feedback.AnswerRecord;
import com.supportforge.feedback.FeedbackSnapshot;
import com.supportforge.intent.IntentLearningSample;
import com.supportforge.knowledge.KnowledgeDocument;
import com.supportforge.memory.MemoryConversationState;
import com.supportforge.memory.MemorySnapshot;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 只读解析器：限定源目录、校验文件大小、解析五类旧JSON并生成稳定条目指纹。 */
@Component
public class LegacyJsonMigrationParser {
    private final ObjectMapper objectMapper;
    private final Path sourceRoot;
    private final long maximumFileBytes;

    public LegacyJsonMigrationParser(
            ObjectMapper objectMapper,
            LegacyMigrationProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.sourceRoot = Path.of(properties.sourceRoot()).toAbsolutePath().normalize();
        this.maximumFileBytes = properties.maximumFileBytes();
    }

    public ParsedLegacyDataset parse(LegacyDatasetType type, String sourceFile) {
        Path source = resolve(sourceFile);
        try {
            long size = Files.size(source);
            if (size == 0 || size > maximumFileBytes) {
                throw new IllegalArgumentException(
                        "迁移文件必须非空且不超过" + maximumFileBytes + "字节"
                );
            }
            List<LegacySourceItem> items = switch (type) {
                case MEMORY -> memory(source);
                case KNOWLEDGE -> knowledge(source);
                case INTENT -> intent(source);
                case FEEDBACK -> feedback(source);
                case EVALUATION -> evaluation(source);
            };
            return new ParsedLegacyDataset(
                    type, source, sha256(source), size, items
            );
        } catch (IOException exception) {
            throw new IllegalStateException("读取迁移文件失败：" + source, exception);
        }
    }

    public Path sourceRoot() {
        return sourceRoot;
    }

    private Path resolve(String sourceFile) {
        if (sourceFile == null || sourceFile.isBlank()) {
            throw new IllegalArgumentException("迁移源文件不能为空");
        }
        Path candidate = sourceRoot.resolve(sourceFile).normalize().toAbsolutePath();
        if (!candidate.startsWith(sourceRoot)) {
            throw new IllegalArgumentException("迁移源文件必须位于受控目录：" + sourceRoot);
        }
        if (!Files.isRegularFile(candidate) || !Files.isReadable(candidate)) {
            throw new IllegalArgumentException("迁移源文件不存在或不可读：" + candidate);
        }
        return candidate;
    }

    private List<LegacySourceItem> memory(Path source) throws IOException {
        MemorySnapshot snapshot = objectMapper.readValue(source, MemorySnapshot.class);
        if (snapshot == null) {
            return List.of();
        }
        LinkedHashMap<String, LegacySourceItem> normalized = new LinkedHashMap<>();
        snapshot.userProfiles().forEach((userId, profile) -> putLast(
                normalized, item(LegacyItemKind.MEMORY_PROFILE,
                        "profile:" + encoded(userId), new LegacyUserProfile(userId, profile))
        ));
        for (MemoryConversationState state : snapshot.conversations()) {
            String key = "conversation:" + encoded(state.userId())
                    + ":" + encoded(state.conversationId());
            putLast(normalized, item(LegacyItemKind.MEMORY_CONVERSATION, key, state));
        }
        return List.copyOf(normalized.values());
    }

    private List<LegacySourceItem> knowledge(Path source) throws IOException {
        KnowledgeDocument[] values = objectMapper.readValue(source, KnowledgeDocument[].class);
        LinkedHashMap<String, LegacySourceItem> normalized = new LinkedHashMap<>();
        if (values != null) {
            for (KnowledgeDocument value : values) {
                putLast(normalized, item(
                        LegacyItemKind.KNOWLEDGE_DOCUMENT,
                        "knowledge:" + encoded(value.id()), value
                ));
            }
        }
        return List.copyOf(normalized.values());
    }

    private List<LegacySourceItem> intent(Path source) throws IOException {
        IntentLearningSample[] values = objectMapper.readValue(source, IntentLearningSample[].class);
        LinkedHashMap<String, LegacySourceItem> normalized = new LinkedHashMap<>();
        if (values != null) {
            for (IntentLearningSample value : values) {
                String normalizedMessage = value.message().strip().toLowerCase(Locale.ROOT);
                putLast(normalized, item(
                        LegacyItemKind.INTENT_SAMPLE,
                        "intent:" + encoded(normalizedMessage), value
                ));
            }
        }
        return List.copyOf(normalized.values());
    }

    private List<LegacySourceItem> feedback(Path source) throws IOException {
        FeedbackSnapshot snapshot = objectMapper.readValue(source, FeedbackSnapshot.class);
        if (snapshot == null) {
            return List.of();
        }
        LinkedHashMap<String, LegacySourceItem> normalized = new LinkedHashMap<>();
        for (AnswerRecord answer : snapshot.answers()) {
            putLast(normalized, item(
                    LegacyItemKind.FEEDBACK_ANSWER,
                    "answer:" + encoded(answer.responseId()), answer
            ));
        }
        for (AnswerFeedback feedback : snapshot.feedbacks()) {
            putLast(normalized, item(
                    LegacyItemKind.FEEDBACK_RATING,
                    "feedback:" + encoded(feedback.responseId()), feedback
            ));
        }
        return List.copyOf(normalized.values());
    }

    private List<LegacySourceItem> evaluation(Path source) throws IOException {
        EvalReport[] reports = objectMapper.readValue(source, EvalReport[].class);
        if (reports == null) {
            return List.of();
        }
        List<LegacySourceItem> items = new ArrayList<>();
        for (int index = 0; index < reports.length; index++) {
            EvalReport report = reports[index];
            items.add(item(
                    LegacyItemKind.EVALUATION_REPORT,
                    "evaluation:" + index + ":" + encoded(report.timestamp()), report
            ));
        }
        return List.copyOf(items);
    }

    private LegacySourceItem item(LegacyItemKind kind, String key, Object payload) {
        String canonical = kind + "\n" + key + "\n"
                + objectMapper.writeValueAsString(payload);
        return new LegacySourceItem(kind, key, sha256(canonical), payload);
    }

    private static void putLast(
            LinkedHashMap<String, LegacySourceItem> values,
            LegacySourceItem item
    ) {
        values.remove(item.sourceKey());
        values.put(item.sourceKey(), item);
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(String value) {
        return java.util.HexFormat.of().formatHex(
                digest().digest(value.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM缺少SHA-256", exception);
        }
    }
}
