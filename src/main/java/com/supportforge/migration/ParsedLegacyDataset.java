package com.supportforge.migration;

import java.nio.file.Path;
import java.util.List;

record ParsedLegacyDataset(
        LegacyDatasetType type,
        Path sourcePath,
        String sourceSha256,
        long sourceSizeBytes,
        List<LegacySourceItem> items
) {
    ParsedLegacyDataset {
        items = List.copyOf(items);
    }
}
