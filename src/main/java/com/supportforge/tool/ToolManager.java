package com.supportforge.tool;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;


@Component
public class ToolManager {


    private final Map<String, ToolRuntime> tools;


    private final ExecutorService executorService;


    private final Map<String, CachedValue> cache =
            new ConcurrentHashMap<>();


    public ToolManager(
            List<Tool> registeredTools,

            @Qualifier("agentExecutor")
            ExecutorService executorService
    ) {

        Map<String, ToolRuntime> runtimeMap =
                new HashMap<>();

        for (Tool tool : registeredTools) {

            String name =
                    normalizeName(tool.name());


            if (name.isBlank()) {
                throw new IllegalStateException(
                        "工具名称不能为空"
                );
            }


            if (runtimeMap.containsKey(name)) {
                throw new IllegalStateException(
                        "工具名称重复：" + name
                );
            }


            CircuitBreaker breaker =
                    new CircuitBreaker(
                            tool.failureThreshold(),
                            tool.recoveryDuration()
                    );


            runtimeMap.put(
                    name,
                    new ToolRuntime(
                            tool,
                            new ToolStats(),
                            breaker
                    )
            );
        }


        this.tools = Map.copyOf(runtimeMap);
        this.executorService = executorService;
    }


    public List<ToolSummary> list() {
        return tools.values()
                .stream()
                .map(runtime ->
                        ToolSummary.from(runtime.tool())
                )
                .sorted(
                        Comparator.comparing(
                                ToolSummary::name
                        )
                )
                .toList();
    }


    public Map<String, ToolStatsSnapshot> stats() {
        Map<String, ToolStatsSnapshot> result =
                new TreeMap<>();


        tools.forEach((name, runtime) ->
                result.put(
                        name,
                        runtime.stats().snapshot(
                                runtime.breaker()
                        )
                )
        );


        return Map.copyOf(result);
    }


    public int evictCache(String toolName) {
        String normalizedName =
                normalizeName(toolName);

        if (normalizedName.isBlank()) {
            return 0;
        }

        String cacheKeyPrefix =
                normalizedName + "|";

        int removedCount = 0;


        for (String key : cache.keySet()) {
            if (key.startsWith(cacheKeyPrefix)
                    && cache.remove(key) != null) {
                removedCount++;
            }
        }

        return removedCount;
    }


    public ToolResult call(
            String toolName,
            Map<String, Object> parameters,
            ToolContext context
    ) {

        long startedAt = System.nanoTime();

        String normalizedName =
                normalizeName(toolName);


        ToolRuntime runtime =
                tools.get(normalizedName);


        if (runtime == null) {
            return ToolResult.failure(
                    normalizedName,
                    "工具不存在：" + normalizedName,
                    elapsedMs(startedAt)
            );
        }


        runtime.stats().recordCall();


        Tool tool = runtime.tool();
        Map<String, Object> safeParameters;

        ToolContext safeContext =
                context == null
                        ? new ToolContext("", "")
                        : context;


        try {


            safeParameters =
                    parameters == null
                            ? Map.of()
                            : Map.copyOf(parameters);


            validateParameters(
                    tool,
                    safeParameters
            );
        } catch (RuntimeException exception) {

            double latency =
                    elapsedMs(startedAt);

            runtime.stats()
                    .recordFailure(latency);

            return ToolResult.failure(
                    normalizedName,
                    safeMessage(exception.getMessage()),
                    latency
            );
        }


        String cacheKey = cacheKey(
                normalizedName,
                tool,
                safeParameters,
                safeContext
        );


        Object cached = readCache(
                cacheKey,
                tool.cacheTtl()
        );


        if (cached != null) {
            try {

                cached = tool.filterCachedResult(cached, safeContext);
            } catch (RuntimeException unavailable) {
                cache.remove(cacheKey);
                double latency = elapsedMs(startedAt);
                runtime.stats().recordFailure(latency);
                return ToolResult.failure(normalizedName, "缓存结果校验失败，请稍后重试", latency);
            }
            double latency =
                    elapsedMs(startedAt);


            runtime.stats()
                    .recordSuccess(latency, true);

            return ToolResult.success(
                    normalizedName,
                    cached,
                    true,
                    latency
            );
        }


        if (!runtime.breaker().allowCall()) {
            double latency =
                    elapsedMs(startedAt);


            runtime.stats()
                    .recordCircuitRejected(latency);

            return ToolResult.failure(
                    normalizedName,
                    "工具熔断中，请稍后重试",
                    latency
            );
        }


        Future<Object> execution;
        var callerIdentity = com.supportforge.security.TenantContext.current().orElse(null);

        try {

            execution = executorService.submit(() ->
                    com.supportforge.security.TenantContext.callWith(callerIdentity, () -> tool.execute(
                            safeParameters,
                            safeContext
                    ))
            );
        } catch (RejectedExecutionException exception) {

            runtime.breaker().recordFailure();

            double latency =
                    elapsedMs(startedAt);

            runtime.stats()
                    .recordFailure(latency);

            return ToolResult.failure(
                    normalizedName,
                    "工具执行器拒绝任务",
                    latency
            );
        }


        long timeoutMs = Math.max(
                1,
                safeDuration(
                        tool.timeout(),
                        Duration.ofSeconds(3)
                ).toMillis()
        );


        try {

            Object data = execution.get(
                    timeoutMs,
                    TimeUnit.MILLISECONDS
            );


            runtime.breaker().recordSuccess();


            writeCache(
                    cacheKey,
                    tool.cacheTtl(),
                    data
            );

            double latency =
                    elapsedMs(startedAt);


            runtime.stats()
                    .recordSuccess(latency, false);

            return ToolResult.success(
                    normalizedName,
                    data,
                    false,
                    latency
            );

        } catch (TimeoutException exception) {

            execution.cancel(true);

            runtime.breaker().recordFailure();

            double latency =
                    elapsedMs(startedAt);


            runtime.stats()
                    .recordTimeout(latency);

            return ToolResult.failure(
                    normalizedName,
                    "工具执行超时",
                    latency
            );

        } catch (InterruptedException exception) {

            execution.cancel(true);


            runtime.breaker().recordFailure();


            Thread.currentThread().interrupt();

            double latency =
                    elapsedMs(startedAt);

            runtime.stats()
                    .recordFailure(latency);

            return ToolResult.failure(
                    normalizedName,
                    "工具调用线程被中断",
                    latency
            );

        } catch (ExecutionException exception) {

            runtime.breaker().recordFailure();


            Throwable cause =
                    exception.getCause();

            String message =
                    cause == null
                            ? exception.getMessage()
                            : cause.getMessage();

            double latency =
                    elapsedMs(startedAt);

            runtime.stats()
                    .recordFailure(latency);

            return ToolResult.failure(
                    normalizedName,
                    safeMessage(message),
                    latency
            );
        }
    }


    private void validateParameters(
            Tool tool,
            Map<String, Object> parameters
    ) {


        Map<String, ToolParameter> definitions =
                tool.parameters()
                        .stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        ToolParameter::name,
                                        parameter -> parameter
                                )
                        );


        for (ToolParameter definition :
                definitions.values()) {


            if (definition.required()
                    && (!parameters.containsKey(
                    definition.name()
            )
                    || parameters.get(
                    definition.name()
            ) == null)) {
                throw new IllegalArgumentException(
                        "工具 "
                                + tool.name()
                                + " 缺少必需参数："
                                + definition.name()
                );
            }
        }


        for (Map.Entry<String, Object> entry :
                parameters.entrySet()) {


            ToolParameter definition =
                    definitions.get(entry.getKey());


            if (definition == null) {
                throw new IllegalArgumentException(
                        "工具 "
                                + tool.name()
                                + " 不支持参数："
                                + entry.getKey()
                );
            }

            Object value = entry.getValue();


            if (value != null
                    && !definition.type()
                    .accepts(value)) {
                throw new IllegalArgumentException(
                        "工具 "
                                + tool.name()
                                + " 参数 "
                                + entry.getKey()
                                + " 类型错误，期望："
                                + definition.type()
                );
            }
        }
    }


    private Object readCache(
            String key,
            Duration ttl
    ) {

        if (ttl == null
                || ttl.isZero()
                || ttl.isNegative()) {
            return null;
        }


        CachedValue cachedValue =
                cache.get(key);


        if (cachedValue == null) {
            return null;
        }


        if (System.nanoTime()
                >= cachedValue.expiresAtNanos()) {

            cache.remove(key, cachedValue);
            return null;
        }

        return cachedValue.data();
    }


    private void writeCache(
            String key,
            Duration ttl,
            Object data
    ) {

        if (ttl == null
                || ttl.isZero()
                || ttl.isNegative()) {
            return;
        }


        long expiresAt =
                System.nanoTime()
                        + ttl.toNanos();


        cache.put(
                key,
                new CachedValue(
                        data,
                        expiresAt
                )
        );


        if (cache.size() > 5_000) {
            removeExpiredCacheEntries();
        }
    }


    private void removeExpiredCacheEntries() {
        long now = System.nanoTime();


        cache.entrySet()
                .removeIf(entry ->
                        now >= entry.getValue()
                                .expiresAtNanos()
                );
    }


    private String cacheKey(
            String toolName,
            Tool tool,
            Map<String, Object> parameters,
            ToolContext context
    ) {

        StringBuilder builder =
                new StringBuilder(toolName)
                        .append('|')
                        .append(canonical(parameters));


        com.supportforge.security.TenantContext.current().ifPresent(identity ->
                builder.append("|identity:").append(canonical(Map.of(
                        "tenant", identity.tenantId().toString(),
                        "actor", identity.actorId(),
                        "roles", identity.roles().stream().map(Enum::name).sorted().toList()))));


        if (tool.cachePerContext()) {
            builder.append('|')
                    .append(canonical(
                            context.userId()
                    ))
                    .append('|')
                    .append(canonical(
                            context.conversationId()
                    ));
        }

        return builder.toString();
    }

    @org.springframework.context.event.EventListener
    public void onPermissionChanged(com.supportforge.security.ResourceAccessService.PermissionChanged event) {
        if ("knowledge_document".equals(event.resourceType())) {

            evictCache("knowledge_search");
        }
    }


    private String canonical(Object value) {

        if (value == null) {
            return "null";
        }


        if (value instanceof Map<?, ?> map) {
            List<String> entries =
                    new ArrayList<>();


            map.entrySet()
                    .stream()
                    .sorted(
                            Comparator.comparing(
                                    entry ->
                                            String.valueOf(
                                                    entry.getKey()
                                            )
                            )
                    )

                    .forEach(entry ->
                            entries.add(
                                    canonical(entry.getKey())
                                            + "="
                                            + canonical(
                                            entry.getValue()
                                    )
                            )
                    );

            return "{"
                    + String.join(",", entries)
                    + "}";
        }


        if (value instanceof List<?> list) {
            return "["
                    + list.stream()
                    .map(this::canonical)
                    .collect(
                            Collectors.joining(",")
                    )
                    + "]";
        }


        String text = String.valueOf(value);

        return value.getClass().getName()
                + ":"
                + text.length()
                + ":"
                + text;
    }


    private Duration safeDuration(
            Duration value,
            Duration fallback
    ) {
        if (value == null
                || value.isZero()
                || value.isNegative()) {
            return fallback;
        }

        return value;
    }


    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }

        return name.strip()
                .toLowerCase();
    }


    private String safeMessage(String message) {
        return message == null
                || message.isBlank()
                ? "工具执行失败"
                : message;
    }


    private double elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt)
                / 1_000_000.0;
    }


    private record ToolRuntime(
            Tool tool,
            ToolStats stats,
            CircuitBreaker breaker
    ) {
    }


    private record CachedValue(
            Object data,
            long expiresAtNanos
    ) {
    }
}
