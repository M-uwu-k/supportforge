package com.supportforge.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyJsonMigrationParserTest {
    @TempDir
    Path root;

    @Test
    void parsesAllFiveLegacyDatasetShapesAndDeduplicatesNaturalKeys() throws Exception {
        write("memory.json", """
                {"conversations":[
                  {"userId":"u1","conversationId":"c1","recentMessages":[],"summary":"","userProfile":{},"updatedAt":"2026-01-01T00:00:00Z"},
                  {"userId":"u1","conversationId":"c1","recentMessages":[],"summary":"new","userProfile":{},"updatedAt":"2026-01-02T00:00:00Z"}
                ],"userProfiles":{"u1":{"language":"zh"}}}
                """);
        write("knowledge.json", """
                [{"id":"k1","title":"标题","category":"TECHNICAL","content":"正文","keywords":["关键字"]}]
                """);
        write("intent.json", """
                [{"message":" 退款 ","intent":"refund"},{"message":"退款","intent":"billing"}]
                """);
        write("feedback.json", """
                {"answers":[{"responseId":"r1","conversationId":"c1","userId":"u1","content":"回答","createdAt":"2026-01-01T00:00:00Z"}],
                 "feedbacks":[{"id":"f1","responseId":"r1","conversationId":"c1","rating":"positive","comment":"好","submittedAt":"2026-01-01T00:01:00Z"}]}
                """);
        write("evaluation.json", """
                [{"timestamp":"2026-01-01T00:00:00Z","total":1,"passed":1,"passRate":1.0,"avgScores":{"accuracy":1.0},"regressions":[],"recommendations":[],"results":[]}]
                """);
        LegacyJsonMigrationParser parser = parser();

        assertThat(parser.parse(LegacyDatasetType.MEMORY, "memory.json").items()).hasSize(2);
        assertThat(parser.parse(LegacyDatasetType.KNOWLEDGE, "knowledge.json").items()).hasSize(1);
        assertThat(parser.parse(LegacyDatasetType.INTENT, "intent.json").items()).hasSize(1);
        assertThat(parser.parse(LegacyDatasetType.FEEDBACK, "feedback.json").items()).hasSize(2);
        assertThat(parser.parse(LegacyDatasetType.EVALUATION, "evaluation.json").items()).hasSize(1);
    }

    @Test
    void rejectsTraversalEmptyAndOversizedSources() throws Exception {
        Path controlled = Files.createDirectories(root.resolve("controlled"));
        Files.writeString(root.resolve("outside.json"), "[]");
        Files.writeString(controlled.resolve("empty.json"), "");
        Files.writeString(controlled.resolve("large.json"), "12345678901");
        LegacyJsonMigrationParser parser = new LegacyJsonMigrationParser(
                mapper(), new LegacyMigrationProperties(controlled.toString(), 10)
        );

        assertThatThrownBy(() -> parser.parse(LegacyDatasetType.KNOWLEDGE, "../outside.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("受控目录");
        assertThatThrownBy(() -> parser.parse(LegacyDatasetType.KNOWLEDGE, "empty.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须非空");
        assertThatThrownBy(() -> parser.parse(LegacyDatasetType.KNOWLEDGE, "large.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不超过");
    }

    private LegacyJsonMigrationParser parser() {
        return new LegacyJsonMigrationParser(
                mapper(), new LegacyMigrationProperties(root.toString(), 1024 * 1024)
        );
    }

    private ObjectMapper mapper() {
        return new ObjectMapper();
    }

    private void write(String name, String content) throws Exception {
        Files.writeString(root.resolve(name), content);
    }
}
