package com.supportforge.feedback;

import com.supportforge.api.dto.FeedbackRequest;
import com.supportforge.persistence.port.FeedbackPersistencePort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.IntStream;


@Service
public class FeedbackService {

    private final FeedbackPersistencePort feedbackStore;


    private final ConcurrentMap<String, FeedbackSnapshot> snapshots =
            new ConcurrentHashMap<>();
    private final Object[] mutationLocks = IntStream.range(0, 64)
            .mapToObj(ignored -> new Object()).toArray();


    public FeedbackService(
            FeedbackPersistencePort feedbackStore
    ) {
        this.feedbackStore = feedbackStore;
        this.snapshots.put(feedbackStore.scopeKey(), feedbackStore.load());
    }


    public AnswerRecord registerAnswer(
            String responseId,
            String conversationId,
            String userId,
            String content
    ) {
        if (feedbackStore.supportsConcurrentIncrementalWrites()) {
            return registerAnswerIncremental(responseId, conversationId, userId, content);
        }
        synchronized (mutationLock(feedbackStore.scopeKey())) {
            return registerAnswerSnapshot(responseId, conversationId, userId, content);
        }
    }

    private AnswerRecord registerAnswerSnapshot(
            String responseId,
            String conversationId,
            String userId,
            String content
    ) {
        FeedbackSnapshot snapshot = currentSnapshot();

        if (responseId == null
                || responseId.isBlank()) {
            throw new IllegalArgumentException(
                    "回答编号不能为空"
            );
        }

        if (conversationId == null
                || conversationId.isBlank()) {
            throw new IllegalArgumentException(
                    "会话编号不能为空"
            );
        }

        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException(
                    "回答内容不能为空"
            );
        }


        String safeUserId =
                userId == null || userId.isBlank()
                        ? "anonymous"
                        : userId.strip();


        Map<String, AnswerRecord> answers =
                answerMap(snapshot);


        if (answers.containsKey(responseId)) {
            throw new IllegalArgumentException(
                    "回答编号重复：" + responseId
            );
        }

        AnswerRecord answer = new AnswerRecord(
                responseId.strip(),
                conversationId.strip(),
                safeUserId,
                content.strip(),
                Instant.now().toString()
        );


        answers.put(
                answer.responseId(),
                answer
        );


        FeedbackSnapshot next =
                feedbackStore.normalize(
                        new FeedbackSnapshot(
                                List.copyOf(answers.values()),
                                snapshot.feedbacks()
                        )
                );


        feedbackStore.save(next);
        publish(next);

        return answer;
    }


    public AnswerFeedback submit(
            FeedbackRequest request
    ) {
        if (feedbackStore.supportsConcurrentIncrementalWrites()) {
            synchronized (mutationLock(feedbackStore.scopeKey() + "/" + request.responseId())) {
                return submitIncremental(request);
            }
        }
        synchronized (mutationLock(feedbackStore.scopeKey())) {
            return submitSnapshot(request);
        }
    }

    private AnswerFeedback submitSnapshot(
            FeedbackRequest request
    ) {
        FeedbackSnapshot snapshot = currentSnapshot();

        FeedbackRating rating =
                FeedbackRating.fromValue(
                        request.rating()
                );


        Map<String, AnswerRecord> answers =
                answerMap(snapshot);


        AnswerRecord answer =
                answers.get(request.responseId());


        if (answer == null) {
            throw new FeedbackTargetNotFoundException(
                    "没有找到可评价的回答："
                            + request.responseId()
            );
        }


        if (!answer.conversationId()
                .equals(request.conversationId())) {
            throw new IllegalArgumentException(
                    "回答编号与会话编号不匹配"
            );
        }


        Map<String, AnswerFeedback> feedbacks =
                feedbackMap(snapshot);


        AnswerFeedback existing =
                feedbacks.get(request.responseId());


        String feedbackId =
                existing == null
                        ? UUID.randomUUID().toString()
                        : existing.id();


        AnswerFeedback feedback =
                new AnswerFeedback(
                        feedbackId,
                        request.responseId(),
                        request.conversationId(),
                        rating.value(),
                        request.comment(),
                        Instant.now().toString()
                );


        feedbacks.remove(
                feedback.responseId()
        );

        feedbacks.put(
                feedback.responseId(),
                feedback
        );


        FeedbackSnapshot next =
                feedbackStore.normalize(
                        new FeedbackSnapshot(
                                snapshot.answers(),
                                List.copyOf(
                                        feedbacks.values()
                                )
                        )
                );

        feedbackStore.save(next);
        publish(next);

        return feedback;
    }


    public List<AnswerFeedback> findAll(
            String conversationId
    ) {
        FeedbackSnapshot snapshot = currentSnapshot();

        String safeConversationId =
                conversationId == null
                        ? ""
                        : conversationId.strip();


        return snapshot.feedbacks()
                .stream()
                .filter(feedback ->
                        safeConversationId.isBlank()
                                || feedback
                                .conversationId()
                                .equals(safeConversationId)
                )

                .sorted(
                        Comparator.comparing(
                                        AnswerFeedback::submittedAt
                                )
                                .reversed()
                )
                .toList();
    }


    public FeedbackSummary summary(
            String conversationId
    ) {

        List<AnswerFeedback> feedbacks =
                findAll(conversationId);


        long positive =
                feedbacks.stream()
                        .filter(feedback ->
                                FeedbackRating.POSITIVE
                                        .value()
                                        .equals(
                                                feedback.rating()
                                        )
                        )
                        .count();


        long negative =
                feedbacks.stream()
                        .filter(feedback ->
                                FeedbackRating.NEGATIVE
                                        .value()
                                        .equals(
                                                feedback.rating()
                                        )
                        )
                        .count();

        long total = feedbacks.size();


        double satisfactionRate =
                total == 0
                        ? 0.0
                        : round4(
                        (double) positive / total
                );


        List<String> negativeComments =
                feedbacks.stream()
                        .filter(feedback ->
                                FeedbackRating.NEGATIVE
                                        .value()
                                        .equals(
                                                feedback.rating()
                                        )
                        )
                        .map(AnswerFeedback::comment)
                        .filter(comment ->
                                comment != null
                                        && !comment.isBlank()
                        )
                        .limit(10)
                        .toList();

        return new FeedbackSummary(
                total,
                positive,
                negative,
                satisfactionRate,
                negativeComments
        );
    }


    public int answerCount() {
        return currentSnapshot().answers().size();
    }

    private AnswerRecord registerAnswerIncremental(
            String responseId, String conversationId, String userId, String content
    ) {
        if (responseId == null || responseId.isBlank()) {
            throw new IllegalArgumentException("回答编号不能为空");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("会话编号不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("回答内容不能为空");
        }
        String scope = feedbackStore.scopeKey();
        FeedbackSnapshot before = currentSnapshot();
        if (answerMap(before).containsKey(responseId)) {
            throw new IllegalArgumentException("回答编号重复：" + responseId);
        }
        AnswerRecord answer = new AnswerRecord(
                responseId.strip(), conversationId.strip(),
                userId == null || userId.isBlank() ? "anonymous" : userId.strip(),
                content.strip(), Instant.now().toString()
        );

        feedbackStore.saveAnswer(before, answer);
        snapshots.compute(scope, (ignored, latest) -> {
            FeedbackSnapshot source = latest == null ? FeedbackSnapshot.empty() : latest;
            Map<String, AnswerRecord> answers = answerMap(source);
            answers.put(answer.responseId(), answer);
            return feedbackStore.normalize(new FeedbackSnapshot(
                    List.copyOf(answers.values()), source.feedbacks()
            ));
        });
        return answer;
    }

    private AnswerFeedback submitIncremental(FeedbackRequest request) {
        FeedbackRating rating = FeedbackRating.fromValue(request.rating());
        String scope = feedbackStore.scopeKey();
        FeedbackSnapshot before = currentSnapshot();
        AnswerRecord answer = answerMap(before).get(request.responseId());
        if (answer == null) {
            throw new FeedbackTargetNotFoundException(
                    "没有找到可评价的回答：" + request.responseId()
            );
        }
        if (!answer.conversationId().equals(request.conversationId())) {
            throw new IllegalArgumentException("回答编号与会话编号不匹配");
        }
        AnswerFeedback existing = feedbackMap(before).get(request.responseId());
        AnswerFeedback feedback = new AnswerFeedback(
                existing == null ? UUID.randomUUID().toString() : existing.id(),
                request.responseId(), request.conversationId(), rating.value(),
                request.comment(), Instant.now().toString()
        );
        feedbackStore.saveFeedback(before, feedback);
        snapshots.compute(scope, (ignored, latest) -> {
            FeedbackSnapshot source = latest == null ? before : latest;
            Map<String, AnswerFeedback> feedbacks = feedbackMap(source);
            feedbacks.remove(feedback.responseId());
            feedbacks.put(feedback.responseId(), feedback);
            return feedbackStore.normalize(new FeedbackSnapshot(
                    source.answers(), List.copyOf(feedbacks.values())
            ));
        });
        return feedback;
    }

    private Object mutationLock(String key) {
        return mutationLocks[Math.floorMod(key.hashCode(), mutationLocks.length)];
    }

    private FeedbackSnapshot currentSnapshot() {
        return snapshots.computeIfAbsent(
                feedbackStore.scopeKey(), ignored -> feedbackStore.load()
        );
    }

    private void publish(FeedbackSnapshot snapshot) {
        snapshots.put(feedbackStore.scopeKey(), snapshot);
    }


    private Map<String, AnswerRecord> answerMap(
            FeedbackSnapshot source
    ) {
        Map<String, AnswerRecord> result =
                new LinkedHashMap<>();

        for (AnswerRecord answer : source.answers()) {
            result.put(
                    answer.responseId(),
                    answer
            );
        }

        return result;
    }


    private Map<String, AnswerFeedback> feedbackMap(
            FeedbackSnapshot source
    ) {
        Map<String, AnswerFeedback> result =
                new LinkedHashMap<>();

        for (AnswerFeedback feedback :
                source.feedbacks()) {

            result.put(
                    feedback.responseId(),
                    feedback
            );
        }

        return result;
    }


    private double round4(double value) {
        return Math.round(value * 10_000.0)
                / 10_000.0;
    }
}
