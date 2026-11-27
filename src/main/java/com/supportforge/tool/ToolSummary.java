package com.supportforge.tool;

import java.util.List;


public record ToolSummary(

        String name,
        String description,

        List<ToolParameter> parameters,

        long timeoutMs,

        long cacheTtlMs,

        int failureThreshold,

        long recoveryMs
) {


    public static ToolSummary from(Tool tool) {
        return new ToolSummary(
                tool.name(),
                tool.description(),
                List.copyOf(tool.parameters()),
                tool.timeout().toMillis(),
                tool.cacheTtl().toMillis(),
                tool.failureThreshold(),
                tool.recoveryDuration().toMillis()
        );
    }
}
