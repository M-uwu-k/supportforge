package com.supportforge.migration;

public record LegacyMigrationRequest(
        LegacyDatasetType datasetType,
        String sourceFile
) {
    public LegacyMigrationRequest {
        if (datasetType == null || sourceFile == null || sourceFile.isBlank()) {
            throw new IllegalArgumentException("datasetType和sourceFile不能为空");
        }
    }
}
