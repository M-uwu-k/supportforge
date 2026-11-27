package com.supportforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 解析、清洗和稳定分块参数。所有长度都按Unicode code unit近似字符计，不是模型token。 */
@ConfigurationProperties(prefix = "supportforge.knowledge.ingestion")
public record KnowledgeIngestionProperties(
        Integer maximumChunkChars,
        Integer minimumChunkChars,
        Integer overlapChars,
        String parserVersion,
        String chunkerVersion
) {

    public KnowledgeIngestionProperties {
        if (maximumChunkChars == null) {
            maximumChunkChars = 800;
        }
        if (minimumChunkChars == null) {
            minimumChunkChars = 120;
        }
        if (overlapChars == null) {
            overlapChars = 120;
        }
        parserVersion = normalize(parserVersion, "text-clean-v1");
        chunkerVersion = normalize(chunkerVersion, "stable-boundary-v1");

        if (maximumChunkChars < 100) {
            throw new IllegalArgumentException("分块最大字符数不能小于100");
        }
        if (minimumChunkChars < 1
                || minimumChunkChars > maximumChunkChars) {
            throw new IllegalArgumentException(
                    "分块最小字符数必须在1到最大字符数之间"
            );
        }
        if (overlapChars < 0
                || overlapChars >= maximumChunkChars) {
            throw new IllegalArgumentException(
                    "分块重叠字符数必须大于等于0且小于最大字符数"
            );
        }
    }

    private static String normalize(
            String value,
            String defaultValue
    ) {
        return value == null || value.isBlank()
                ? defaultValue
                : value.strip();
    }
}
