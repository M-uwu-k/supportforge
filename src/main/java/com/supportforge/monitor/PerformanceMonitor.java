package com.supportforge.monitor;

import com.supportforge.agent.AgentResponse;
import com.supportforge.knowledge.KnowledgeCategory;
import com.supportforge.llm.LlmResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

import com.supportforge.agent.AgentType;

import java.util.concurrent.TimeUnit;


@Component
public class PerformanceMonitor {


    private final MeterRegistry meterRegistry;


    private final Counter chatRequests;

    private final Counter chatEscalations;

    private final Counter chatKnowledgeResponses;

    private final Timer chatLatency;


    public PerformanceMonitor(
            MeterRegistry meterRegistry
    ) {
        this.meterRegistry = meterRegistry;


        this.chatRequests = Counter.builder(
                        "supportforge.chat.requests"
                )
                .description("SupportForge处理的聊天请求总数")
                .register(meterRegistry);


        this.chatEscalations = Counter.builder(
                        "supportforge.chat.escalations"
                )
                .description("被标记为需要升级的请求总数")
                .register(meterRegistry);


        this.chatKnowledgeResponses = Counter.builder(
                        "supportforge.chat.knowledge.responses"
                )
                .description("使用知识库生成的回答总数")
                .register(meterRegistry);


        this.chatLatency = Timer.builder(
                        "supportforge.chat.latency"
                )
                .description("聊天请求总处理耗时")
                .register(meterRegistry);
    }


    public void recordChat(
            boolean escalated,
            boolean knowledgeUsed,
            double latencyMs
    ) {

        chatRequests.increment();


        chatLatency.record(
                toDuration(latencyMs)
        );


        if (escalated) {
            chatEscalations.increment();
        }


        if (knowledgeUsed) {
            chatKnowledgeResponses.increment();
        }
    }


    public void recordAgent(
            AgentResponse response
    ) {
        String agent = response.agentType().value();

        String success =
                Boolean.toString(response.success());


        meterRegistry.counter(
                "supportforge.agent.executions",
                "agent", agent,
                "success", success
        ).increment();


        meterRegistry.timer(
                "supportforge.agent.latency",
                "agent", agent
        ).record(
                toDuration(response.latencyMs())
        );
    }


    public void recordLlm(
            LlmResponse response
    ) {
        String model = safeTag(response.model());
        String success =
                Boolean.toString(response.success());


        meterRegistry.counter(
                "supportforge.llm.calls",
                "model", model,
                "success", success
        ).increment();


        meterRegistry.timer(
                "supportforge.llm.latency",
                "model", model
        ).record(
                toDuration(response.latencyMs())
        );


            meterRegistry.counter(
                    "supportforge.llm.tokens",
                    "model", model,
                    "type", "input"
            ).increment(
                    Math.max(0, response.inputTokens())
            );


            meterRegistry.counter(
                    "supportforge.llm.tokens",
                    "model", model,
                    "type", "output"
            ).increment(
                    Math.max(0, response.outputTokens())
            );
    }


    public void recordRag(
            KnowledgeCategory category,
            boolean hit
    ) {


        meterRegistry.counter(
                "supportforge.rag.retrievals",
                "category",
                category.name().toLowerCase(),
                "result",
                hit ? "hit" : "miss"
        ).increment();
    }


    public double routingPenalty(
            AgentType agentType
    ) {

        String agentName =
                agentType.value();


        Counter successfulCounter =
                meterRegistry.find(
                                "supportforge.agent.executions"
                        )
                        .tag(
                                "agent",
                                agentName
                        )
                        .tag(
                                "success",
                                "true"
                        )
                        .counter();


        Counter failedCounter =
                meterRegistry.find(
                                "supportforge.agent.executions"
                        )
                        .tag(
                                "agent",
                                agentName
                        )
                        .tag(
                                "success",
                                "false"
                        )
                        .counter();


        double successful =
                counterValue(successfulCounter);

        double failed =
                counterValue(failedCounter);

        double total =
                successful + failed;


        if (total == 0.0) {
            return 0.0;
        }


        double successRate =
                successful / total;


        Timer latencyTimer =
                meterRegistry.find(
                                "supportforge.agent.latency"
                        )
                        .tag(
                                "agent",
                                agentName
                        )
                        .timer();


        double averageLatencyMs =
                latencyTimer == null
                        || latencyTimer.count() == 0
                        ? 0.0
                        : latencyTimer.mean(
                        TimeUnit.MILLISECONDS
                );

        double penalty = 0.0;


        if (successRate < 0.90) {
            penalty += Math.min(
                    0.50,
                    (0.90 - successRate) * 2.0
            );
        }


        if (averageLatencyMs > 3_000.0) {
            penalty += Math.min(
                    0.40,
                    (averageLatencyMs - 3_000.0)
                            / 10_000.0
            );
        }


        return Math.min(
                penalty,
                0.90
        );
    }


    private double counterValue(
            Counter counter
    ) {
        return counter == null
                ? 0.0
                : counter.count();
    }


    private Duration toDuration(
            double latencyMs
    ) {


        if (!Double.isFinite(latencyMs)
                || latencyMs < 0) {
            return Duration.ZERO;
        }


        long nanoseconds =
                (long) (latencyMs * 1_000_000.0);


        return Duration.ofNanos(nanoseconds);
    }


    private String safeTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }


        return value;
    }
}
