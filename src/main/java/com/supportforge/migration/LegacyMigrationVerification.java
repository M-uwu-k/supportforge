package com.supportforge.migration;

import java.util.List;
import java.util.UUID;

public record LegacyMigrationVerification(
        UUID runId,
        boolean matched,
        boolean sourceChecksumMatches,
        boolean rowCountsMatch,
        int recordedImportedItems,
        int existingTargetItems,
        List<String> missingSourceKeys,
        String status
) {
    public LegacyMigrationVerification {
        missingSourceKeys = List.copyOf(missingSourceKeys);
    }

}
