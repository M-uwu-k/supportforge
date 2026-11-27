package com.supportforge.intent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PersistentTemplateEmbeddingIntentRecognizerTest {

    @TempDir
    Path tempDir;


    @Test
    void shouldRestorePersistedSamplesWhenRecognizerIsCreated() {
        IntentLearningStore store = store();
        store.upsert(
                "重启后仍是物流问题",
                IntentCategory.LOGISTICS
        );

        TemplateEmbeddingIntentRecognizer recognizer =
                new TemplateEmbeddingIntentRecognizer(
                        256,
                        store
                );

        IntentCandidate candidate = recognizer.recognize(
                "重启后仍是物流问题"
        );

        assertEquals(IntentCategory.LOGISTICS, candidate.intent());
        assertEquals(1.0, candidate.confidence(), 0.000001);
        assertEquals(1, recognizer.learnedTemplateCount());
    }


    @Test
    void shouldPersistRecognizerLearningAcrossNewInstances() {
        IntentLearningStore firstStore = store();
        TemplateEmbeddingIntentRecognizer firstRecognizer =
                new TemplateEmbeddingIntentRecognizer(
                        256,
                        firstStore
                );

        assertTrue(firstRecognizer.learn(
                "这个说法应该转人工",
                IntentCategory.HUMAN_HANDOFF
        ));

        IntentLearningStore restartedStore = store();
        TemplateEmbeddingIntentRecognizer restartedRecognizer =
                new TemplateEmbeddingIntentRecognizer(
                        256,
                        restartedStore
                );

        assertEquals(
                IntentCategory.HUMAN_HANDOFF,
                restartedRecognizer
                        .recognize("这个说法应该转人工")
                        .intent()
        );
        assertEquals(1, restartedRecognizer.learnedTemplateCount());
    }


    private IntentLearningStore store() {
        return new IntentLearningStore(
                new ObjectMapper(),
                tempDir.resolve("intent-learning.json").toString(),
                10
        );
    }
}
