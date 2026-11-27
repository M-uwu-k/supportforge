package com.supportforge.embedding;

/** Qwen3查询侧需要instruction，文档侧保持原文，因此必须显式区分。 */
public enum EmbeddingPurpose {
    QUERY,
    DOCUMENT
}
