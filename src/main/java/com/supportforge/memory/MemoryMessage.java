package com.supportforge.memory;

import java.time.Instant;


public record MemoryMessage(
        MemoryRole role,
        String content,
        Instant createdAt
) {


    public static MemoryMessage now(
            MemoryRole role,
            String content
    ) {

        return new MemoryMessage(
                role,
                content,
                Instant.now()
        );
    }
}
