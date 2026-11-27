package com.supportforge.memory;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserProfileExtractorTest {

    @Test
    void shouldExtractExplicitLanguageAndResponseStyle() {
        UserProfileExtractor extractor = new UserProfileExtractor(true);

        Map<String, String> profile = extractor.update(
                Map.of(),
                "以后请用英文回答，简洁一点"
        );

        assertEquals("英文", profile.get("preferred_language"));
        assertEquals("简洁", profile.get("response_style"));
    }

    @Test
    void shouldOverrideOnlyPreferenceMentionedByCurrentMessage() {
        UserProfileExtractor extractor = new UserProfileExtractor(true);

        Map<String, String> profile = extractor.update(
                Map.of(
                        "preferred_language", "英文",
                        "response_style", "简洁"
                ),
                "请用中文详细一点回答"
        );

        assertEquals(
                Map.of(
                        "preferred_language", "中文",
                        "response_style", "详细"
                ),
                profile
        );
    }

    @Test
    void shouldKeepExistingProfileWhenExtractionIsDisabled() {
        UserProfileExtractor extractor = new UserProfileExtractor(false);
        Map<String, String> existing = Map.of(
                "preferred_language",
                "中文"
        );

        Map<String, String> profile = extractor.update(
                existing,
                "以后请用英文回答"
        );

        assertEquals(existing, profile);
        assertThrows(
                UnsupportedOperationException.class,
                () -> profile.put("response_style", "详细")
        );
    }
}
