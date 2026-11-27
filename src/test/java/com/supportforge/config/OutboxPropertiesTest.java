package com.supportforge.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutboxPropertiesTest {

    @Test
    void suppliesDefaultsAndCapsExponentialBackoff() {
        OutboxProperties properties = new OutboxProperties(
                0, null, 0, null, null
        );

        assertEquals(50, properties.claimBatchSize());
        assertEquals(Duration.ofSeconds(30), properties.leaseDuration());
        assertEquals(Duration.ofSeconds(1), properties.retryDelayForAttempt(1));
        assertEquals(Duration.ofSeconds(8), properties.retryDelayForAttempt(4));
        assertEquals(Duration.ofMinutes(5), properties.retryDelayForAttempt(20));
    }

    @Test
    void rejectsRetryMaximumBelowInitialDelay() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OutboxProperties(
                        10,
                        Duration.ofSeconds(30),
                        10,
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(1)
                )
        );
    }
}
