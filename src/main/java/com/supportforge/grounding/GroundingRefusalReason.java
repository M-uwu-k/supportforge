package com.supportforge.grounding;

public enum GroundingRefusalReason {
    NONE,
    NO_EVIDENCE,
    LOW_RELEVANCE,
    LOW_QUERY_COVERAGE,
    UNSAFE_KNOWLEDGE,
    RERANKER_UNAVAILABLE,
    STALE_EVIDENCE
}
