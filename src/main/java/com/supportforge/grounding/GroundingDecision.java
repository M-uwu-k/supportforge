package com.supportforge.grounding;

import java.util.List;
import java.util.UUID;

/** Grounding Harness的完整判定；拒答时promptContext必为空。 */
public record GroundingDecision(
        UUID traceId,
        boolean allowed,
        GroundingRefusalReason refusalReason,
        String response,
        double queryCoverage,
        List<GroundingEvidence> evidence,
        String promptContext
) {
    public GroundingDecision {
        evidence = List.copyOf(evidence);
        promptContext = promptContext == null ? "" : promptContext;
        if (!allowed && !promptContext.isEmpty()) {
            throw new IllegalArgumentException("拒答决策不能携带模型上下文");
        }
    }

    public GroundingDecision(
            boolean allowed,
            GroundingRefusalReason refusalReason,
            String response,
            double queryCoverage,
            List<GroundingEvidence> evidence,
            String promptContext
    ) {
        this(null, allowed, refusalReason, response, queryCoverage,
                evidence, promptContext);
    }

    public GroundingDecision withTraceId(UUID value) {
        if (value == null) {
            throw new IllegalArgumentException("检索轨迹ID不能为空");
        }
        return new GroundingDecision(
                value, allowed, refusalReason, response, queryCoverage,
                evidence, promptContext
        );
    }
}
