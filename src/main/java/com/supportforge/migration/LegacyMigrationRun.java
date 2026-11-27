package com.supportforge.migration;

import java.time.Instant;
import java.util.UUID;

public record LegacyMigrationRun(
        UUID id,
        UUID tenantId,
        LegacyDatasetType datasetType,
        String sourceFile,
        String sourceSha256,
        long sourceSizeBytes,
        String status,
        int discoveredItems,
        int importedItems,
        int skippedItems,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Instant rolledBackAt
) { }
