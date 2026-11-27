package com.supportforge.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;


class DatabasePropertiesTest {

    @Test
    void acceptsValidPostgresConfiguration() {

        DatabaseProperties properties = properties(
                "jdbc:postgresql://localhost:5432/supportforge",
                "supportforge"
        );

        assertDoesNotThrow(properties::validateForConnection);
    }

    @Test
    void rejectsMissingUrlOnlyWhenDatabaseIsEnabled() {


        DatabaseProperties properties = properties("", "supportforge");

        assertThrows(
                IllegalStateException.class,
                properties::validateForConnection
        );
    }

    @Test
    void rejectsNonPostgresJdbcUrl() {

        DatabaseProperties properties = properties(
                "jdbc:mysql://localhost:3306/supportforge",
                "supportforge"
        );

        assertThrows(
                IllegalStateException.class,
                properties::validateForConnection
        );
    }

    @Test
    void rejectsInvalidPoolBounds() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new DatabaseProperties(
                        "jdbc:postgresql://localhost:5432/supportforge",
                        "supportforge",
                        "",
                        "supportforge",
                        2,
                        3,
                        Duration.ofSeconds(5),
                        true
                )
        );
    }


    private DatabaseProperties properties(
            String url,
            String username
    ) {
        return new DatabaseProperties(
                url,
                username,
                "",
                "supportforge",
                10,
                1,
                Duration.ofSeconds(5),
                true
        );
    }
}

