package com.supportforge.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaIndexingPropertiesTest {

    @Test
    void suppliesLocalKraftAndDltDefaults() {
        KafkaIndexingProperties properties = new KafkaIndexingProperties(
                null, null, null, null, null,
                0, (short) 0, null, null, null, 0, null
        );

        assertEquals("localhost:29092", properties.bootstrapServers());
        assertEquals(6, properties.partitions());
        assertEquals(5, properties.consumerMaximumAttempts());
        assertEquals(Duration.ofMinutes(2), properties.workerLeaseDuration());
    }

    @Test
    void rejectsMainTopicEqualToDlt() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new KafkaIndexingProperties(
                        "localhost:9092", "same", "same", "group", "client",
                        1, (short) 1, Duration.ofSeconds(1),
                        Duration.ofSeconds(1), Duration.ZERO, 1,
                        Duration.ofSeconds(10)
                )
        );
    }
}
