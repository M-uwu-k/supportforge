package com.supportforge.tool;

import java.time.Duration;
import java.util.List;
import java.util.Map;


public interface Tool {


    String name();


    String description();


    List<ToolParameter> parameters();


    Object execute(
            Map<String, Object> parameters,
            ToolContext context
    );


    default Duration timeout() {
        return Duration.ofSeconds(3);
    }


    default Duration cacheTtl() {
        return Duration.ZERO;
    }


    default boolean cachePerContext() {
        return true;
    }

    /** 缓存命中后再次检查结果是否可供当前身份读取；敏感工具可过滤已失效的权限。 */
    default Object filterCachedResult(Object data, ToolContext context) {
        return data;
    }


    default int failureThreshold() {
        return 5;
    }


    default Duration recoveryDuration() {
        return Duration.ofSeconds(30);
    }
}
