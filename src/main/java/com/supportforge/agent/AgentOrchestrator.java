package com.supportforge.agent;

import com.supportforge.intent.IntentCategory;
import com.supportforge.intent.UrgencyLevel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;


import com.supportforge.knowledge.KnowledgeSource;
import java.util.LinkedHashMap;


import com.supportforge.monitor.PerformanceMonitor;


@Component
public class AgentOrchestrator {


    private static final double SUPPORT_THRESHOLD = 0.45;

    private final Map<AgentType, Agent> agents;
    private final Executor agentExecutor;

    private final PerformanceMonitor performanceMonitor;
    public AgentOrchestrator(
            List<Agent> agentList,
            @Qualifier("agentExecutor")
            Executor agentExecutor,
            PerformanceMonitor performanceMonitor
    ) {
        EnumMap<AgentType, Agent> agentMap =
                new EnumMap<>(AgentType.class);

        for (Agent agent : agentList) {
            agentMap.put(agent.type(), agent);
        }

        this.agents = Map.copyOf(agentMap);
        this.agentExecutor = agentExecutor;
        this.performanceMonitor = performanceMonitor;
    }

    public OrchestratorResult run(AgentRequest request) {
        long startTime = System.nanoTime();


        RoutingDecision decision = decide(request);

        if (decision.multiAgent()) {
            return runParallel(request, decision, startTime);
        }

        return runSingle(request, decision, startTime);
    }


    private OrchestratorResult runSingle(
            AgentRequest request,
            RoutingDecision decision,
            long startTime
    ) {

        AgentResponse response =
                executeWithFallback(decision.primaryAgent(), request);

        return new OrchestratorResult(
                response.content(),
                response.agentType(),
                List.of(response.agentType()),
                decision.primaryAgent(),
                List.of(),
                decision.reason(),
                decision.confidence(),
                shouldEscalate(request, List.of(response)),
                elapsedMs(startTime),
                response.knowledgeSources()
        );
    }

    private OrchestratorResult runParallel(
            AgentRequest request,
            RoutingDecision decision,
            long startTime
    ) {
        List<CompletableFuture<AgentResponse>> futures =
                decision.agentTypes()
                        .stream()
                        .map(agentType ->
                                CompletableFuture.supplyAsync(
                                        () -> executeWithFallback(
                                                agentType,
                                                request
                                        ),
                                        agentExecutor
                                ).exceptionally(exception -> {


                                    AgentResponse failedResponse =
                                            new AgentResponse(
                                                    agentType,
                                                    agentType.value()
                                                            + " Agent 执行异常",
                                                    false,
                                                    0.0,
                                                    0.0,
                                                    true,
                                                    List.of()
                                            );


                                    if (!request.ruleOnly()) performanceMonitor.recordAgent(failedResponse);


                                    return failedResponse;
                                })
                        )
                        .toList();


        List<AgentResponse> responses = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        List<AgentResponse> successfulResponses =
                responses.stream()
                        .filter(AgentResponse::success)
                        .toList();

        String combinedResponse;

        if (successfulResponses.isEmpty()) {
            combinedResponse =
                    "抱歉，所有 Agent 都未能成功处理本次请求。";
        } else {
            combinedResponse = successfulResponses.stream()
                    .map(response -> {
                        String role =
                                response.agentType()
                                        == decision.primaryAgent()
                                        ? "主处理"
                                        : "辅助处理";

                        return "["
                                + response.agentType().value()
                                + " - "
                                + role
                                + "]\n"
                                + response.content();
                    })
                    .collect(Collectors.joining("\n\n"));
        }

        List<AgentType> actualAgentTypes =
                successfulResponses.stream()
                        .map(AgentResponse::agentType)
                        .distinct()
                        .toList();

        if (actualAgentTypes.isEmpty()) {
            actualAgentTypes = decision.agentTypes();
        }

        List<KnowledgeSource> knowledgeSources =
                collectKnowledgeSources(
                        successfulResponses
                );

        return new OrchestratorResult(
                combinedResponse,
                decision.primaryAgent(),
                actualAgentTypes,
                decision.primaryAgent(),
                decision.supportingAgents(),
                decision.reason(),
                decision.confidence(),
                shouldEscalate(request, responses),
                elapsedMs(startTime),
                knowledgeSources
        );
    }


    private RoutingDecision decide(AgentRequest request) {


        if (request.urgency() == UrgencyLevel.CRITICAL
                || request.intent() == IntentCategory.ESCALATION
                || request.intent() == IntentCategory.HUMAN_HANDOFF) {

            return new RoutingDecision(
                    AgentType.ESCALATION,
                    List.of(),
                    "请求紧急程度较高，或用户明确要求人工处理",
                    1.0
            );
        }


        EnumMap<AgentType, Double> scores =
                calculateDomainScores(request);


        EnumMap<AgentType, Double> monitorPenalties =
                applyMonitoringPenalties(scores);


        Map.Entry<AgentType, Double> primaryEntry =
                scores.entrySet()
                        .stream()
                        .filter(entry ->
                                entry.getKey() != AgentType.ESCALATION
                                        && agents.containsKey(
                                        entry.getKey()
                                )
                        )
                        .max(Map.Entry.comparingByValue())
                        .orElseThrow();

        AgentType primaryAgent = primaryEntry.getKey();
        double primaryScore = primaryEntry.getValue();


        List<AgentType> supportingAgents =
                scores.entrySet()
                        .stream()
                        .filter(entry ->
                                entry.getKey() != primaryAgent
                                        && entry.getKey()
                                        != AgentType.GENERAL
                                        && entry.getKey()
                                        != AgentType.ESCALATION
                                        && entry.getValue()
                                        >= SUPPORT_THRESHOLD
                                        && agents.containsKey(
                                        entry.getKey()
                                )
                        )
                        .sorted(
                                Map.Entry
                                        .<AgentType, Double>
                                                comparingByValue()
                                        .reversed()
                        )
                        .map(Map.Entry::getKey)
                        .toList();


        String scoreText = scores.entrySet()
                .stream()
                .sorted(
                        Map.Entry
                                .<AgentType, Double>
                                        comparingByValue()
                                .reversed()
                )
                .map(entry ->
                        entry.getKey().value()
                                + "="
                                + String.format(
                                Locale.ROOT,
                                "%.2f",
                                entry.getValue()
                        )
                )
                .collect(Collectors.joining(", "));


        String penaltyText =
                monitorPenalties.entrySet()
                        .stream()
                        .filter(entry ->
                                entry.getValue() > 0.0
                        )
                        .sorted(
                                Map.Entry
                                        .<AgentType, Double>
                                                comparingByValue()
                                        .reversed()
                        )
                        .map(entry ->
                                entry.getKey().value()
                                        + "="
                                        + String.format(
                                        Locale.ROOT,
                                        "%.2f",
                                        entry.getValue()
                                )
                        )
                        .collect(
                                Collectors.joining(", ")
                        );


        if (penaltyText.isBlank()) {
            penaltyText = "无";
        }

        String reason =
                "监控调整后领域得分："
                        + scoreText
                        + "；监控降权："
                        + penaltyText
                        + "；主 Agent："
                        + primaryAgent.value()
                        + "；辅助 Agent："
                        + supportingAgents.stream()
                        .map(AgentType::value)
                        .toList();


        return new RoutingDecision(
                primaryAgent,
                supportingAgents,
                reason,
                Math.min(primaryScore, 1.0)
        );
    }


    private EnumMap<AgentType, Double>
    applyMonitoringPenalties(
            EnumMap<AgentType, Double> scores
    ) {

        EnumMap<AgentType, Double> penalties =
                new EnumMap<>(AgentType.class);


        scores.replaceAll((agentType, score) -> {

            double penalty =
                    performanceMonitor.routingPenalty(
                            agentType
                    );

            penalties.put(
                    agentType,
                    penalty
            );


            return score
                    * Math.max(
                    0.0,
                    1.0 - penalty
            );
        });

        return penalties;
    }

    private EnumMap<AgentType, Double> calculateDomainScores(
            AgentRequest request
    ) {
        EnumMap<AgentType, Double> scores =
                new EnumMap<>(AgentType.class);

        scores.put(AgentType.GENERAL, 0.10);
        scores.put(AgentType.TECHNICAL, 0.0);
        scores.put(AgentType.BILLING, 0.0);

        switch (request.intent()) {
            case TECHNICAL,
                    TECHNICAL_LOGIN,
                    TECHNICAL_CRASH ->
                    addScore(scores, AgentType.TECHNICAL, 0.75);

            case BILLING,
                    REFUND,
                    INVOICE,
                    PAYMENT_ISSUE,
                    ACCOUNT,
                    ACCOUNT_SECURITY ->
                    addScore(scores, AgentType.BILLING, 0.75);

            default ->
                    addScore(scores, AgentType.GENERAL, 0.55);
        }

        String message = request.message().toLowerCase(Locale.ROOT);

        int technicalHits = countHits(
                message,
                List.of(
                        "崩溃",
                        "报错",
                        "error",
                        "crash",
                        "无法登录",
                        "登录失败",
                        "验证码",
                        "401",
                        "403",
                        "404",
                        "500"
                )
        );

        int billingHits = countHits(
                message,
                List.of(
                        "重复扣款",
                        "多扣",
                        "退款",
                        "退货",
                        "扣款",
                        "发票",
                        "账单",
                        "支付",
                        "订阅",
                        "refund",
                        "invoice"
                )
        );

        int generalHits = countHits(
                message,
                List.of(
                        "订单",
                        "物流",
                        "快递",
                        "配送",
                        "会员",
                        "积分",
                        "咨询",
                        "帮助"
                )
        );

        addScore(
                scores,
                AgentType.TECHNICAL,
                Math.min(0.45, technicalHits * 0.18)
        );

        addScore(
                scores,
                AgentType.BILLING,
                Math.min(0.45, billingHits * 0.18)
        );

        addScore(
                scores,
                AgentType.GENERAL,
                Math.min(0.35, generalHits * 0.12)
        );

        if (hasEntity(request, "error_code")) {
            addScore(scores, AgentType.TECHNICAL, 0.20);
        }

        if (hasEntity(request, "amount")) {
            addScore(scores, AgentType.BILLING, 0.15);
        }

        if (hasEntity(request, "order_id")) {
            addScore(scores, AgentType.GENERAL, 0.10);
        }

        return scores;
    }

    private AgentResponse executeWithFallback(
            AgentType requestedType,
            AgentRequest request
    ) {
        Agent agent = findAgent(requestedType);

        AgentResponse response =
                agent.handle(request);


        if (!request.ruleOnly()) performanceMonitor.recordAgent(response);

        if (!response.success()
                && response.agentType()
                != AgentType.GENERAL) {

            Agent generalAgent =
                    agents.get(AgentType.GENERAL);

            if (generalAgent != null) {
                AgentResponse fallbackResponse =
                        generalAgent.handle(request);


                if (!request.ruleOnly()) performanceMonitor.recordAgent(fallbackResponse);

                return fallbackResponse;
            }
        }

        return response;
    }

    private Agent findAgent(AgentType type) {
        Agent agent = agents.get(type);

        if (agent != null) {
            return agent;
        }

        Agent generalAgent = agents.get(AgentType.GENERAL);

        if (generalAgent == null) {
            throw new IllegalStateException(
                    "没有找到 " + type + " Agent，也没有 GeneralAgent"
            );
        }

        return generalAgent;
    }

    private boolean shouldEscalate(
            AgentRequest request,
            List<AgentResponse> responses
    ) {
        boolean agentRequestedEscalation =
                responses.stream()
                        .anyMatch(AgentResponse::escalate);

        return agentRequestedEscalation
                || request.urgency() == UrgencyLevel.CRITICAL
                || request.intent() == IntentCategory.ESCALATION
                || request.intent() == IntentCategory.HUMAN_HANDOFF;
    }

    private boolean hasEntity(
            AgentRequest request,
            String entityName
    ) {
        return request.entities() != null
                && request.entities().containsKey(entityName)
                && !request.entities().get(entityName).isEmpty();
    }

    private int countHits(
            String message,
            List<String> keywords
    ) {
        int hits = 0;

        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                hits++;
            }
        }

        return hits;
    }

    private void addScore(
            EnumMap<AgentType, Double> scores,
            AgentType type,
            double value
    ) {
        scores.merge(type, value, Double::sum);
    }

    private double elapsedMs(long startTime) {
        return (System.nanoTime() - startTime) / 1_000_000.0;
    }

    private List<KnowledgeSource> collectKnowledgeSources(
            List<AgentResponse> responses
    ) {


        Map<String, KnowledgeSource> uniqueSources =
                new LinkedHashMap<>();

        for (AgentResponse response : responses) {
            for (KnowledgeSource source :
                    response.knowledgeSources()) {

                uniqueSources.merge(
                        source.id(),
                        source,
                        (existing, candidate) ->
                                candidate.score()
                                        > existing.score()
                                        ? candidate
                                        : existing
                );
            }
        }

        return List.copyOf(
                uniqueSources.values()
        );
    }
}


