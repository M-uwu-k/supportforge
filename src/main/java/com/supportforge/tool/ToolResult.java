package com.supportforge.tool;


public record ToolResult(

        boolean success,

        Object data,

        String toolName,

        String error,

        boolean cached,

        double latencyMs
) {


    public static ToolResult success(
            String toolName,
            Object data,
            double latencyMs
    ) {
        return success(
                toolName,
                data,
                false,
                latencyMs
        );
    }


    public static ToolResult success(
            String toolName,
            Object data,
            boolean cached,
            double latencyMs
    ) {
        return new ToolResult(
                true,
                data,
                toolName,
                null,
                cached,
                latencyMs
        );
    }


    public static ToolResult failure(
            String toolName,
            String error,
            double latencyMs
    ) {
        return new ToolResult(
                false,
                null,
                toolName,
                error,
                false,
                latencyMs
        );
    }
}
