package com.supportforge.feedback;

import com.supportforge.persistence.port.FeedbackPersistencePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@Component
@ConditionalOnProperty(
        prefix = "supportforge.persistence", name = "feedback",
        havingValue = "json", matchIfMissing = true
)
public class FeedbackStore implements FeedbackPersistencePort {

    private final ObjectMapper objectMapper;
    private final Path storagePath;
    private final int maxRecords;


    public FeedbackStore(
            ObjectMapper objectMapper,

            @Value(
                    "${supportforge.feedback.storage-path:"
                            + "data/feedback.json}"
            )
            String storagePath,

            @Value(
                    "${supportforge.feedback.max-records:1000}"
            )
            int maxRecords
    ) {

        if (maxRecords < 1) {
            throw new IllegalArgumentException(
                    "反馈最大保留数量必须大于0"
            );
        }

        this.objectMapper = objectMapper;

        this.storagePath = Path.of(storagePath)
                .toAbsolutePath()
                .normalize();
        this.maxRecords = maxRecords;
    }


    public FeedbackSnapshot load() {
        try {


            if (Files.notExists(storagePath)
                    || Files.size(storagePath) == 0) {
                return FeedbackSnapshot.empty();
            }


            FeedbackSnapshot snapshot =
                    objectMapper.readValue(
                            storagePath,
                            FeedbackSnapshot.class
                    );

            return normalize(snapshot);
        } catch (IOException | RuntimeException exception) {

            throw new IllegalStateException(
                    "读取反馈文件失败：" + storagePath,
                    exception
            );
        }
    }


    public synchronized void save(
            FeedbackSnapshot snapshot
    ) {


        FeedbackSnapshot normalized =
                normalize(snapshot);

        try {
            Path parent = storagePath.getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }


            Path temporaryPath =
                    storagePath.resolveSibling(
                            storagePath.getFileName()
                                    + ".tmp"
                    );


            objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(
                            temporaryPath,
                            normalized
                    );

            try {

                Files.move(
                        temporaryPath,
                        storagePath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (
                    AtomicMoveNotSupportedException exception
            ) {


                Files.move(
                        temporaryPath,
                        storagePath,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException | RuntimeException exception) {

            throw new IllegalStateException(
                    "保存反馈文件失败：" + storagePath,
                    exception
            );
        }
    }


    public FeedbackSnapshot normalize(
            FeedbackSnapshot snapshot
    ) {
        if (snapshot == null) {
            return FeedbackSnapshot.empty();
        }


        Map<String, AnswerRecord> answersById =
                new LinkedHashMap<>();

        for (AnswerRecord answer : snapshot.answers()) {


            answersById.remove(answer.responseId());
            answersById.put(
                    answer.responseId(),
                    answer
            );
        }


        List<AnswerRecord> allAnswers =
                List.copyOf(
                        answersById.values()
                );


        int fromIndex =
                Math.max(
                        0,
                        allAnswers.size() - maxRecords
                );


        List<AnswerRecord> retainedAnswers =
                List.copyOf(
                        allAnswers.subList(
                                fromIndex,
                                allAnswers.size()
                        )
                );


        Set<String> retainedResponseIds =
                retainedAnswers.stream()
                        .map(AnswerRecord::responseId)
                        .collect(Collectors.toSet());


        Map<String, AnswerFeedback> feedbackByResponseId =
                new LinkedHashMap<>();

        for (AnswerFeedback feedback :
                snapshot.feedbacks()) {


            if (!retainedResponseIds.contains(
                    feedback.responseId()
            )) {
                continue;
            }


            feedbackByResponseId.remove(
                    feedback.responseId()
            );

            feedbackByResponseId.put(
                    feedback.responseId(),
                    feedback
            );
        }


        return new FeedbackSnapshot(
                retainedAnswers,
                List.copyOf(
                        feedbackByResponseId.values()
                )
        );
    }


    public Path storagePath() {
        return storagePath;
    }


    public int maxRecords() {
        return maxRecords;
    }
}
