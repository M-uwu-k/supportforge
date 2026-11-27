package com.supportforge.agent;

import com.supportforge.intent.IntentCategory;
import com.supportforge.intent.UrgencyLevel;
import com.supportforge.knowledge.KnowledgeCategory;
import com.supportforge.knowledge.KnowledgeSource;
import com.supportforge.monitor.PerformanceMonitor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class AgentOrchestratorTest {

    private ExecutorService executor;
    private SimpleMeterRegistry meterRegistry;
    private PerformanceMonitor performanceMonitor;

    @BeforeEach
    void setUp() {

        executor =
                Executors.newVirtualThreadPerTaskExecutor();

        meterRegistry =
                new SimpleMeterRegistry();

        performanceMonitor =
                new PerformanceMonitor(
                        meterRegistry
                );
    }

    @AfterEach
    void tearDown() {
        executor.close();
        meterRegistry.close();
    }

    @Test
    void shouldRouteTechnicalLoginToTechnicalAgent() {
        Agent generalAgent = successfulAgent(
                AgentType.GENERAL,
                "通用回答",
                List.of()
        );

        Agent technicalAgent = successfulAgent(
                AgentType.TECHNICAL,
                "技术回答：请检查登录信息。",
                List.of()
        );

        Agent billingAgent = successfulAgent(
                AgentType.BILLING,
                "账单回答",
                List.of()
        );

        AgentOrchestrator orchestrator =
                createOrchestrator(
                        generalAgent,
                        technicalAgent,
                        billingAgent
                );

        AgentRequest request = createRequest(
                "登录失败，错误码401",
                IntentCategory.TECHNICAL_LOGIN,
                "technical",
                UrgencyLevel.LOW,
                Map.of(
                        "error_code", List.of("401"),
                        "amount", List.of(),
                        "order_id", List.of()
                )
        );

        OrchestratorResult result =
                orchestrator.run(request);

        assertAll(
                () -> assertEquals(
                        AgentType.TECHNICAL,
                        result.primaryAgent()
                ),

                () -> assertEquals(
                        AgentType.TECHNICAL,
                        result.agentType()
                ),

                () -> assertEquals(
                        List.of(AgentType.TECHNICAL),
                        result.agentTypes()
                ),

                () -> assertTrue(
                        result.supportingAgents()
                                .isEmpty()
                ),

                () -> assertEquals(
                        "技术回答：请检查登录信息。",
                        result.response()
                ),

                () -> assertFalse(
                        result.escalated()
                )
        );
    }

    @Test
    void shouldRunTechnicalAndBillingAgents() {
        AtomicInteger technicalExecutions =
                new AtomicInteger();

        AtomicInteger billingExecutions =
                new AtomicInteger();

        KnowledgeSource sharedLowScore =
                new KnowledgeSource(
                        "shared-policy",
                        "共享处理规则",
                        KnowledgeCategory.BILLING,
                        1.2,
                        List.of("扣款")
                );

        KnowledgeSource sharedHighScore =
                new KnowledgeSource(
                        "shared-policy",
                        "共享处理规则",
                        KnowledgeCategory.BILLING,
                        2.8,
                        List.of(
                                "重复扣款",
                                "扣款"
                        )
                );

        KnowledgeSource refundSource =
                new KnowledgeSource(
                        "billing-refund",
                        "退款申请规则",
                        KnowledgeCategory.BILLING,
                        1.7,
                        List.of("退款")
                );

        Agent generalAgent = successfulAgent(
                AgentType.GENERAL,
                "通用回答",
                List.of()
        );

        Agent technicalAgent = new StubAgent(
                AgentType.TECHNICAL,
                request -> {
                    technicalExecutions.incrementAndGet();

                    return successfulResponse(
                            AgentType.TECHNICAL,
                            "技术 Agent 回答",
                            List.of(sharedLowScore)
                    );
                }
        );

        Agent billingAgent = new StubAgent(
                AgentType.BILLING,
                request -> {
                    billingExecutions.incrementAndGet();

                    return successfulResponse(
                            AgentType.BILLING,
                            "账单 Agent 回答",
                            List.of(
                                    sharedHighScore,
                                    refundSource
                            )
                    );
                }
        );

        AgentOrchestrator orchestrator =
                createOrchestrator(
                        generalAgent,
                        technicalAgent,
                        billingAgent
                );

        AgentRequest request = createRequest(
                "登录时报错401，同时重复扣款99元并申请退款",
                IntentCategory.TECHNICAL_LOGIN,
                "technical",
                UrgencyLevel.LOW,
                Map.of(
                        "error_code", List.of("401"),
                        "amount", List.of("99元"),
                        "order_id", List.of()
                )
        );

        OrchestratorResult result =
                orchestrator.run(request);

        assertAll(
                () -> assertEquals(
                        1,
                        technicalExecutions.get()
                ),

                () -> assertEquals(
                        1,
                        billingExecutions.get()
                ),

                () -> assertEquals(
                        AgentType.TECHNICAL,
                        result.primaryAgent()
                ),

                () -> assertEquals(
                        List.of(AgentType.BILLING),
                        result.supportingAgents()
                ),

                () -> assertTrue(
                        result.agentTypes().contains(
                                AgentType.TECHNICAL
                        )
                ),

                () -> assertTrue(
                        result.agentTypes().contains(
                                AgentType.BILLING
                        )
                ),

                () -> assertTrue(
                        result.response().contains(
                                "[technical - 主处理]"
                        )
                ),

                () -> assertTrue(
                        result.response().contains(
                                "[billing - 辅助处理]"
                        )
                )
        );


        assertEquals(
                2,
                result.knowledgeSources().size()
        );

        KnowledgeSource mergedSharedSource =
                result.knowledgeSources()
                        .stream()
                        .filter(source ->
                                source.id().equals(
                                        "shared-policy"
                                )
                        )
                        .findFirst()
                        .orElseThrow();

        assertAll(
                () -> assertEquals(
                        2.8,
                        mergedSharedSource.score()
                ),

                () -> assertEquals(
                        List.of(
                                "重复扣款",
                                "扣款"
                        ),
                        mergedSharedSource
                                .matchedKeywords()
                )
        );
    }

    @Test
    void shouldFallbackToGeneralWhenTechnicalFails() {
        Agent technicalAgent = new StubAgent(
                AgentType.TECHNICAL,
                request -> new AgentResponse(
                        AgentType.TECHNICAL,
                        "技术 Agent 处理失败",
                        false,
                        0.0,
                        2.0,
                        false,
                        List.of()
                )
        );

        Agent generalAgent = successfulAgent(
                AgentType.GENERAL,
                "已由通用 Agent 降级处理",
                List.of()
        );

        Agent billingAgent = successfulAgent(
                AgentType.BILLING,
                "账单回答",
                List.of()
        );

        AgentOrchestrator orchestrator =
                createOrchestrator(
                        generalAgent,
                        technicalAgent,
                        billingAgent
                );

        AgentRequest request = createRequest(
                "登录失败401",
                IntentCategory.TECHNICAL_LOGIN,
                "technical",
                UrgencyLevel.LOW,
                Map.of(
                        "error_code", List.of("401")
                )
        );

        OrchestratorResult result =
                orchestrator.run(request);

        assertAll(


                () -> assertEquals(
                        AgentType.TECHNICAL,
                        result.primaryAgent()
                ),


                () -> assertEquals(
                        AgentType.GENERAL,
                        result.agentType()
                ),

                () -> assertEquals(
                        List.of(AgentType.GENERAL),
                        result.agentTypes()
                ),

                () -> assertEquals(
                        "已由通用 Agent 降级处理",
                        result.response()
                )
        );

        double technicalFailures =
                meterRegistry.get(
                                "supportforge.agent.executions"
                        )
                        .tag("agent", "technical")
                        .tag("success", "false")
                        .counter()
                        .count();

        double generalSuccesses =
                meterRegistry.get(
                                "supportforge.agent.executions"
                        )
                        .tag("agent", "general")
                        .tag("success", "true")
                        .counter()
                        .count();

        assertAll(
                () -> assertEquals(
                        1.0,
                        technicalFailures
                ),

                () -> assertEquals(
                        1.0,
                        generalSuccesses
                )
        );
    }

    @Test
    void shouldEscalateCriticalRequest() {
        Agent generalAgent = successfulAgent(
                AgentType.GENERAL,
                "已经记录紧急请求",
                List.of()
        );

        Agent technicalAgent = successfulAgent(
                AgentType.TECHNICAL,
                "技术回答",
                List.of()
        );

        Agent billingAgent = successfulAgent(
                AgentType.BILLING,
                "账单回答",
                List.of()
        );


        AgentOrchestrator orchestrator =
                createOrchestrator(
                        generalAgent,
                        technicalAgent,
                        billingAgent
                );

        AgentRequest request = createRequest(
                "这是紧急问题，请立刻处理",
                IntentCategory.REQUEST,
                "request",
                UrgencyLevel.CRITICAL,
                Map.of()
        );

        OrchestratorResult result =
                orchestrator.run(request);

        assertAll(
                () -> assertEquals(
                        AgentType.ESCALATION,
                        result.primaryAgent()
                ),

                () -> assertEquals(
                        AgentType.GENERAL,
                        result.agentType()
                ),

                () -> assertTrue(
                        result.escalated()
                ),

                () -> assertEquals(
                        "已经记录紧急请求",
                        result.response()
                )
        );
    }


    @Test
    void shouldLowerRoutingScoreForUnhealthyAgent() {


        performanceMonitor.recordAgent(
                new AgentResponse(
                        AgentType.TECHNICAL,
                        "技术处理失败",
                        false,
                        0.0,
                        7_000.0,
                        false,
                        List.of()
                )
        );

        AgentOrchestrator orchestrator =
                createOrchestrator(
                        successfulAgent(
                                AgentType.GENERAL,
                                "通用回答",
                                List.of()
                        ),
                        successfulAgent(
                                AgentType.TECHNICAL,
                                "技术回答",
                                List.of()
                        ),
                        successfulAgent(
                                AgentType.BILLING,
                                "账单回答",
                                List.of()
                        )
                );

        AgentRequest request = createRequest(
                "登录失败，同时支付出现问题",
                IntentCategory.TECHNICAL_LOGIN,
                "technical",
                UrgencyLevel.LOW,
                Map.of()
        );

        OrchestratorResult result =
                orchestrator.run(request);


        assertAll(
                () -> assertEquals(
                        AgentType.BILLING,
                        result.primaryAgent()
                ),
                () -> assertEquals(
                        AgentType.BILLING,
                        result.agentType()
                ),
                () -> assertTrue(
                        result.routingReason()
                                .contains(
                                        "technical=0.90"
                                )
                )
        );
    }

    private AgentOrchestrator createOrchestrator(
            Agent... agents
    ) {
        return new AgentOrchestrator(
                List.of(agents),
                executor,
                performanceMonitor
        );
    }

    private Agent successfulAgent(
            AgentType type,
            String content,
            List<KnowledgeSource> sources
    ) {
        return new StubAgent(
                type,
                request -> successfulResponse(
                        type,
                        content,
                        sources
                )
        );
    }

    private AgentResponse successfulResponse(
            AgentType type,
            String content,
            List<KnowledgeSource> sources
    ) {
        return new AgentResponse(
                type,
                content,
                true,
                1.0,
                1.0,
                false,
                sources
        );
    }

    private AgentRequest createRequest(
            String message,
            IntentCategory intent,
            String intentGroup,
            UrgencyLevel urgency,
            Map<String, List<String>> entities
    ) {
        return new AgentRequest(
                message,
                "test-user",
                "test-conversation",
                "",
                entities,
                intent,
                intentGroup,
                urgency,
                0.90
        );
    }

    private record StubAgent(
            AgentType type,
            Function<AgentRequest, AgentResponse> handler
    ) implements Agent {

        @Override
        public AgentResponse handle(
                AgentRequest request
        ) {
            return handler.apply(request);
        }
    }
}
