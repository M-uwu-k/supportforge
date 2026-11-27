package com.supportforge.migration;

import java.util.List;
import java.util.Map;

public record LegacyMigrationPreview(
        LegacyDatasetType datasetType,
        String sourceFile,
        String sourceSha256,
        long sourceSizeBytes,
        int discoveredItems,
        Map<String, Integer> itemsByKind,
        List<String> warnings
) {
    public LegacyMigrationPreview {
        itemsByKind = Map.copyOf(itemsByKind);
        warnings = List.copyOf(warnings);
    }
}
