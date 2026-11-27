package com.supportforge.feedback;

import com.supportforge.api.dto.FeedbackRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedbackServiceTest {

    @TempDir
    Path tempDir;

    private int storeIndex;

    @Test
    void shouldCreateFeedbackAndCalculateSummary() {
        FeedbackService service = serviceAtNewPath();
        register(service, "r1", "c1");
        register(service, "r2", "c1");
        service.submit(request("r1", "c1", "positive", "有帮助"));
        service.submit(request("r2", "c1", "negative", "步骤不完整"));

        FeedbackSummary summary = service.summary("c1");

        assertEquals(2, summary.total());
        assertEquals(1, summary.positive());
        assertEquals(1, summary.negative());
        assertEquals(0.5, summary.satisfactionRate());
        assertEquals(List.of("步骤不完整"), summary.recentNegativeComments());
    }

    @Test
    void shouldUpdateExistingResponseWithoutIncreasingTotal() {
        FeedbackService service = serviceAtNewPath();
        register(service, "r1", "c1");
        AnswerFeedback first = service.submit(
                request("r1", "c1", "positive", "原评价")
        );

        AnswerFeedback updated = service.submit(
                request("r1", "c1", "negative", "改为差评")
        );

        assertEquals(first.id(), updated.id());
        assertEquals("negative", updated.rating());
        assertEquals(1, service.findAll(null).size());
        assertEquals(1, service.summary(null).negative());
    }

    @Test
    void shouldRejectUnknownTargetRatingAndConversationChange() {
        FeedbackService service = serviceAtNewPath();
        FeedbackTargetNotFoundException notFound = assertThrows(
                FeedbackTargetNotFoundException.class,
                () -> service.submit(request("missing", "c1", "positive", ""))
        );
        assertEquals("没有找到可评价的回答：missing", notFound.getMessage());

        register(service, "r1", "c1");
        IllegalArgumentException ratingError = assertThrows(
                IllegalArgumentException.class,
                () -> service.submit(request("r1", "c1", "neutral", ""))
        );
        assertTrue(ratingError.getMessage().contains("只支持 positive 或 negative"));

        IllegalArgumentException conversationError = assertThrows(
                IllegalArgumentException.class,
                () -> service.submit(request("r1", "c2", "negative", ""))
        );
        assertEquals("回答编号与会话编号不匹配", conversationError.getMessage());
    }

    @Test
    void shouldRestoreAnswersAndFeedbackAfterRestart() {
        Path path = tempDir.resolve("restart/feedback.json");
        FeedbackStore store = store(path, 10);
        FeedbackService first = new FeedbackService(store);
        register(first, "r1", "c1");
        AnswerFeedback saved = first.submit(
                request("r1", "c1", "positive", "已解决")
        );

        FeedbackService restarted = new FeedbackService(store);

        assertEquals(1, restarted.answerCount());
        assertEquals(List.of(saved), restarted.findAll("c1"));
        AnswerFeedback updated = restarted.submit(
                request("r1", "c1", "negative", "重新检查后未解决")
        );
        assertEquals(saved.id(), updated.id());
    }

    private FeedbackService serviceAtNewPath() {
        Path path = tempDir.resolve(
                "service-" + storeIndex++ + "/feedback.json"
        );
        return new FeedbackService(store(path, 100));
    }

    private FeedbackStore store(Path path, int maxRecords) {
        return new FeedbackStore(new ObjectMapper(), path.toString(), maxRecords);
    }

    private void register(
            FeedbackService service,
            String responseId,
            String conversationId
    ) {
        service.registerAnswer(
                responseId,
                conversationId,
                "test-user",
                "测试回答"
        );
    }

    private FeedbackRequest request(
            String responseId,
            String conversationId,
            String rating,
            String comment
    ) {
        return new FeedbackRequest(responseId, conversationId, rating, comment);
    }
}
