package com.supportforge.feedback;

import com.supportforge.api.dto.FeedbackRequest;
import com.supportforge.persistence.port.FeedbackPersistencePort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeedbackServiceConcurrencyTest {

    @Test
    void concurrentIncrementalWritesDoNotLoseAnswersOrFeedback() throws Exception {
        FeedbackService service = new FeedbackService(new ConcurrentIncrementalPort());
        List<String> responseIds = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(12)) {
            List<Future<?>> registrations = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                String responseId = "response-" + index;
                responseIds.add(responseId);
                registrations.add(executor.submit(() -> service.registerAnswer(
                        responseId, "conversation-" + responseId,
                        "user", "answer"
                )));
            }
            for (Future<?> future : registrations) {
                future.get();
            }
            assertEquals(100, service.answerCount());

            List<Future<?>> feedbackWrites = new ArrayList<>();
            for (String responseId : responseIds) {
                feedbackWrites.add(executor.submit(() -> service.submit(new FeedbackRequest(
                        responseId, "conversation-" + responseId,
                        "positive", "concurrent"
                ))));
            }
            for (Future<?> future : feedbackWrites) {
                future.get();
            }
        }

        assertEquals(100, service.findAll(null).size());
        assertEquals(100, service.summary(null).positive());
    }

    private static final class ConcurrentIncrementalPort implements FeedbackPersistencePort {
        @Override
        public FeedbackSnapshot load() {
            return FeedbackSnapshot.empty();
        }

        @Override
        public void save(FeedbackSnapshot snapshot) {
            throw new AssertionError("增量实现不应退化为全量快照写入");
        }

        @Override
        public void saveAnswer(FeedbackSnapshot snapshot, AnswerRecord answer) {

        }

        @Override
        public void saveFeedback(FeedbackSnapshot snapshot, AnswerFeedback feedback) {

        }

        @Override
        public boolean supportsConcurrentIncrementalWrites() {
            return true;
        }

        @Override
        public FeedbackSnapshot normalize(FeedbackSnapshot snapshot) {
            return snapshot;
        }
    }
}
