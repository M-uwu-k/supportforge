package com.supportforge.grounding;

import java.util.List;
import java.util.UUID;

/** 聊天实际执行的企业证据检查结果；只有模型正文引用并通过校验的证据才放进 evidence。 */
public record ChatGrounding(
        UUID retrievalTraceId,
        String status,
        List<GroundingEvidence> evidence,
        List<String> issues
) {
    public ChatGrounding {
        evidence = List.copyOf(evidence);
        issues = List.copyOf(issues);
    }

    public boolean grounded() {
        return "grounded".equals(status) && issues.isEmpty() && !evidence.isEmpty();
    }
}
