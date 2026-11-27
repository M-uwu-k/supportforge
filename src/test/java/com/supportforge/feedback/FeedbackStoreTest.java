package com.supportforge.feedback;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSaveAndLoadSnapshot() {
        Path path = tempDir.resolve("nested/feedback.json");
        FeedbackStore store = store(path, 10);
        FeedbackSnapshot snapshot = new FeedbackSnapshot(
                List.of(answer("r1", "c1")),
                List.of(feedback("f1", "r1", "c1"))
        );

        store.save(snapshot);

        assertTrue(Files.exists(path));
        assertEquals(snapshot, store.load());
    }

    @Test
    void shouldEvictOldAnswersAndOrphanFeedback() {
        FeedbackStore store = store(
                tempDir.resolve("limited/feedback.json"),
                2
        );
        FeedbackSnapshot snapshot = new FeedbackSnapshot(
                List.of(
                        answer("r1", "c1"),
                        answer("r2", "c1"),
                        answer("r3", "c2")
                ),
                List.of(
                        feedback("f1", "r1", "c1"),
                        feedback("f2", "r2", "c1"),
                        feedback("f3", "missing", "c9")
                )
        );

        FeedbackSnapshot normalized = store.normalize(snapshot);

        assertEquals(List.of("r2", "r3"), normalized.answers().stream()
                .map(AnswerRecord::responseId)
                .toList());
        assertEquals(List.of("r2"), normalized.feedbacks().stream()
                .map(AnswerFeedback::responseId)
                .toList());
    }

    private FeedbackStore store(Path path, int maxRecords) {
        return new FeedbackStore(new ObjectMapper(), path.toString(), maxRecords);
    }

    private AnswerRecord answer(String responseId, String conversationId) {
        return new AnswerRecord(
                responseId,
                conversationId,
                "user",
                "answer",
                "2026-11-01T00:00:00Z"
        );
    }

    private AnswerFeedback feedback(
            String id,
            String responseId,
            String conversationId
    ) {
        return new AnswerFeedback(
                id,
                responseId,
                conversationId,
                "positive",
                "ok",
                "2026-11-01T00:00:01Z"
        );
    }
}
