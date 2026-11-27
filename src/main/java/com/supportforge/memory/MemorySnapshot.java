package com.supportforge.memory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public record MemorySnapshot(
        List<MemoryConversationState> conversations,
        Map<String, Map<String, String>> userProfiles
) {


    public MemorySnapshot {
        conversations = conversations == null
                ? List.of()
                : List.copyOf(conversations);


        Map<String, Map<String, String>> copiedProfiles =
                new LinkedHashMap<>();

        if (userProfiles != null) {

            userProfiles.forEach((userId, profile) -> {
                String normalizedUserId =
                        normalizeUserId(userId);


                Map<String, String> copiedProfile =
                        profile == null
                                ? Map.of()
                                : Map.copyOf(profile);

                if (!copiedProfile.isEmpty()) {

                    copiedProfiles.put(
                            normalizedUserId,
                            copiedProfile
                    );
                }
            });
        }


        userProfiles =
                Collections.unmodifiableMap(
                        copiedProfiles
                );
    }


    public MemorySnapshot(
            List<MemoryConversationState> conversations
    ) {
        this(
                conversations,
                Map.of()
        );
    }


    public static MemorySnapshot empty() {
        return new MemorySnapshot(
                List.of(),
                Map.of()
        );
    }


    private static String normalizeUserId(
            String userId
    ) {
        return userId == null || userId.isBlank()
                ? "anonymous"
                : userId.strip();
    }
}
