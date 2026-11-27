package com.supportforge.intent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentLearningStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistAndRestoreSamplesAfterRestart() {
        Path path = tempDir.resolve("nested/intent-learning.json");
        IntentLearningStore first = store(path, 10);

        assertTrue(first.upsert(
                "包裹卡在中转站",
                IntentCategory.LOGISTICS
        ));
        assertTrue(Files.exists(path));

        IntentLearningStore restarted = store(path, 10);

        assertEquals(1, restarted.findAll().size());
        assertEquals("包裹卡在中转站",
                restarted.findAll().getFirst().message());
        assertEquals(IntentCategory.LOGISTICS,
                restarted.findAll().getFirst().category());
    }

    @Test
    void shouldIgnoreDuplicateAndReplaceChangedCorrection() {
        IntentLearningStore store = store(
                tempDir.resolve("learning.json"),
                10
        );

        assertTrue(store.upsert(
                "  申请退款  ",
                IntentCategory.REFUND
        ));
        assertFalse(store.upsert(
                "申请退款",
                IntentCategory.REFUND
        ));
        assertTrue(store.upsert(
                "申请退款",
                IntentCategory.REQUEST
        ));

        assertEquals(1, store.findAll().size());
        assertEquals(IntentCategory.REQUEST,
                store.findAll().getFirst().category());
    }

    @Test
    void shouldRetainNewestSamplesWithinConfiguredLimit() {
        IntentLearningStore store = store(
                tempDir.resolve("limited.json"),
                2
        );

        store.upsert("样本A", IntentCategory.QUERY);
        store.upsert("样本B", IntentCategory.REFUND);
        store.upsert("样本C", IntentCategory.LOGISTICS);

        List<IntentLearningSample> samples = store.findAll();

        assertEquals(2, samples.size());
        assertEquals(List.of("样本B", "样本C"),
                samples.stream()
                        .map(IntentLearningSample::message)
                        .toList());
    }

    @Test
    void shouldRejectInvalidConfigurationSampleAndCorruptedFile()
            throws IOException {
        assertThrows(
                IllegalArgumentException.class,
                () -> store(tempDir.resolve("bad-limit.json"), 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new IntentLearningSample(
                        "测试",
                        IntentCategory.OTHER
                )
        );

        Path corrupted = tempDir.resolve("corrupted.json");
        Files.writeString(corrupted, "{这不是合法JSON");

        assertThrows(
                IllegalStateException.class,
                () -> store(corrupted, 10)
        );
    }

    private IntentLearningStore store(
            Path path,
            int maxSamples
    ) {
        return new IntentLearningStore(
                new ObjectMapper(),
                path.toString(),
                maxSamples
        );
    }
}
