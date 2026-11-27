package com.supportforge.migration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("supportforge.migration")
public record LegacyMigrationProperties(
        @DefaultValue("data") String sourceRoot,
        @DefaultValue("104857600") long maximumFileBytes
) {
    public LegacyMigrationProperties {
        if (sourceRoot == null || sourceRoot.isBlank()) {
            throw new IllegalArgumentException("迁移源目录不能为空");
        }
        if (maximumFileBytes < 1) {
            throw new IllegalArgumentException("迁移文件上限必须大于0");
        }
    }
}
