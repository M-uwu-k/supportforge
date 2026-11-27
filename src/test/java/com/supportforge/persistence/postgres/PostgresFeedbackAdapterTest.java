package com.supportforge.persistence.postgres;

import com.supportforge.config.DatabaseProperties;
import com.supportforge.feedback.AnswerFeedback;
import com.supportforge.feedback.AnswerRecord;
import com.supportforge.feedback.FeedbackSnapshot;
import com.supportforge.security.TenantContextProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class PostgresFeedbackAdapterTest {

    @Test
    void normalizeKeepsLatestAnswersAndOnlyTheirFeedback() {
        PostgresFeedbackAdapter adapter = adapter(2);
        FeedbackSnapshot normalized = adapter.normalize(new FeedbackSnapshot(
                List.of(
                        answer("r1", "2026-01-01T00:00:00Z"),
                        answer("r2", "2026-01-02T00:00:00Z"),
                        answer("r3", "2026-01-03T00:00:00Z")
                ),
                List.of(
                        feedback("f1", "r1"),
                        feedback("f2", "r2"),
                        feedback("f3", "r3")
                )
        ));

        assertEquals(List.of("r2", "r3"), normalized.answers().stream()
                .map(AnswerRecord::responseId).toList());
        assertEquals(List.of("r2", "r3"), normalized.feedbacks().stream()
                .map(AnswerFeedback::responseId).toList());
    }

    @Test
    void rejectsInvalidRetentionLimit() {
        assertThrows(IllegalArgumentException.class, () -> adapter(0));
    }

    private static PostgresFeedbackAdapter adapter(int maxRecords) {
        return new PostgresFeedbackAdapter(
                mock(DataSource.class), mock(ObjectMapper.class),
                new TenantContextProvider("00000000-0000-0000-0000-000000000001"),
                database(), maxRecords
        );
    }

    private static AnswerRecord answer(String id, String timestamp) {
        return new AnswerRecord(id, "conversation", "user", "answer", timestamp);
    }

    private static AnswerFeedback feedback(String id, String responseId) {
        return new AnswerFeedback(
                id, responseId, "conversation", "positive", "verified",
                "2026-01-04T00:00:00Z"
        );
    }

    private static DatabaseProperties database() {
        return new DatabaseProperties(
                "", "", "", "supportforge", 2, 0,
                Duration.ofSeconds(1), true
        );
    }
}
