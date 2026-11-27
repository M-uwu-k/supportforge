package com.supportforge.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationReportStoreTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldReturnEmptyHistoryWhenFileDoesNotExist() {
        EvaluationReportStore store = storeAt("missing/reports.json", 10);

        assertTrue(store.load().isEmpty());
    }

    @Test
    void shouldSaveAndLoadCompleteReport() {
        Path reportPath = tempDir.resolve("nested/reports.json");
        EvaluationReportStore store = new EvaluationReportStore(
                objectMapper,
                reportPath.toString(),
                10
        );
        EvalReport report = report("2026-10-31T01:00:00Z", 0.9);

        store.save(List.of(report));

        assertTrue(Files.exists(reportPath));
        assertEquals(List.of(report), store.load());
    }

    @Test
    void shouldKeepOnlyNewestReports() {
        EvaluationReportStore store = storeAt("limited/reports.json", 2);
        EvalReport first = report("first", 0.7);
        EvalReport second = report("second", 0.8);
        EvalReport third = report("third", 0.9);

        store.save(List.of(first, second, third));

        assertEquals(List.of(second, third), store.load());
        assertEquals(
                List.of(second, third),
                store.keepLatest(List.of(first, second, third))
        );
    }

    @Test
    void shouldRejectInvalidHistoryLimit() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> storeAt("invalid/reports.json", 0)
        );

        assertEquals(
                "评测报告最大保留数量必须大于0",
                exception.getMessage()
        );
    }

    private EvaluationReportStore storeAt(
            String relativePath,
            int maxHistory
    ) {
        return new EvaluationReportStore(
                objectMapper,
                tempDir.resolve(relativePath).toString(),
                maxHistory
        );
    }

    private EvalReport report(String timestamp, double score) {
        EvalResult result = new EvalResult(
                "dialog_0",
                score >= 0.75,
                Map.of("overall", score),
                "测试报告",
                Map.of("question", "你好", "response", "您好")
        );

        return new EvalReport(
                timestamp,
                1,
                result.passed() ? 1 : 0,
                result.passed() ? 1.0 : 0.0,
                Map.of("relevance", score),
                List.of(),
                List.of("测试建议"),
                List.of(result)
        );
    }
}
