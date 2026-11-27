package com.supportforge.migration;

import java.util.Map;

record LegacyUserProfile(String userId, Map<String, String> profile) {
    LegacyUserProfile {
        profile = profile == null ? Map.of() : Map.copyOf(profile);
    }
}
