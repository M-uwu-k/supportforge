package com.supportforge.tool;


public record ToolParameter(
        String name,
        ToolValueType type,
        boolean required,
        String description
) {


    public ToolParameter {


        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "工具参数名称不能为空"
            );
        }


        if (type == null) {
            throw new IllegalArgumentException(
                    "工具参数类型不能为空"
            );
        }


        name = name.strip();

        description = description == null
                ? ""
                : description.strip();

    }
}
