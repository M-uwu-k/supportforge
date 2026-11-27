package com.supportforge.api;

import com.supportforge.agent.AgentOrchestrator;
import com.supportforge.agent.AgentRequest;
import com.supportforge.agent.AgentType;
import com.supportforge.agent.OrchestratorResult;
import com.supportforge.api.dto.ChatRequest;
import com.supportforge.api.dto.ChatResponse;
import com.supportforge.intent.IntentRecognizer;
import com.supportforge.intent.IntentResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.supportforge.memory.ConversationMemory;
import com.supportforge.memory.MemoryMessage;
import com.supportforge.memory.MemoryRole;

import java.util.UUID;

import com.supportforge.monitor.PerformanceMonitor;

import com.supportforge.agent.AnswerVerifier;

import com.supportforge.feedback.FeedbackService;
import com.supportforge.grounding.EnterpriseChatService;
import org.springframework.beans.factory.ObjectProvider;

@RestController
public class ChatController {

    private final IntentRecognizer intentRecognizer;
    private final AgentOrchestrator orchestrator;

    private final ConversationMemory conversationMemory;

    private final PerformanceMonitor performanceMonitor;

    private final AnswerVerifier answerVerifier;

    private final FeedbackService feedbackService;
    private final ObjectProvider<EnterpriseChatService> enterpriseChat;

    public ChatController(
            IntentRecognizer intentRecognizer,
            AgentOrchestrator orchestrator,
            ConversationMemory conversationMemory,
            PerformanceMonitor performanceMonitor,
            AnswerVerifier answerVerifier, FeedbackService feedbackService,
            ObjectProvider<EnterpriseChatService> enterpriseChat
    ) {
        this.intentRecognizer = intentRecognizer;
        this.orchestrator = orchestrator;
        this.conversationMemory = conversationMemory;
        this.performanceMonitor = performanceMonitor;
        this.answerVerifier = answerVerifier;
        this.feedbackService = feedbackService;
        this.enterpriseChat = enterpriseChat;
    }

    @PostMapping("/chat")
    @com.supportforge.security.RequiresAnyRole({com.supportforge.security.EnterpriseRole.ADMIN,
            com.supportforge.security.EnterpriseRole.AGENT})
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        long startTime = System.nanoTime();

        String conversationId = request.conversationId();

        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
        }


        String context =
                conversationMemory.buildContext(
                        request.userId(),
                        conversationId,
                        request.message()
                );


        IntentResult intentResult =
                intentRecognizer.recognize(
                        request.message(),
                        context
                );

        AgentRequest agentRequest = new AgentRequest(
                request.message(),
                request.userId(),
                conversationId,
                context,
                intentResult.entities(),
                intentResult.intent(),
                intentResult.intentGroup(),
                intentResult.urgency(),
                intentResult.confidence()
        );


        EnterpriseChatService enterprise = enterpriseChat.getIfAvailable();
        OrchestratorResult result = enterprise == null
                ? orchestrator.run(agentRequest)
                : enterprise.run(agentRequest);

        AnswerVerifier.VerificationResult verification =
                answerVerifier.verify(
                        agentRequest,
                        result
                );

        boolean escalated =
                result.escalated()
                        || verification.needsEscalation();


        conversationMemory.append(
                request.userId(),
                conversationId,
                MemoryMessage.now(
                        MemoryRole.USER,
                        request.message()
                )
        );


        conversationMemory.append(
                request.userId(),
                conversationId,
                MemoryMessage.now(
                        MemoryRole.ASSISTANT,
                        result.response()
                )
        );

        double latencyMs =
                (System.nanoTime() - startTime) / 1_000_000.0;

        boolean knowledgeUsed =
                !result.knowledgeSources().isEmpty();


        String responseId =
                UUID.randomUUID().toString();


        feedbackService.registerAnswer(
                responseId,
                conversationId,
                request.userId(),
                result.response()
        );

        performanceMonitor.recordChat(
                escalated,
                knowledgeUsed,
                latencyMs
        );

        return new ChatResponse(
                conversationId,
                responseId,
                result.response(),
                intentResult.intent().value(),
                intentResult.intentGroup(),
                result.agentType().value(),
                result.agentTypes()
                        .stream()
                        .map(AgentType::value)
                        .toList(),
                result.primaryAgent().value(),
                result.supportingAgents()
                        .stream()
                        .map(AgentType::value)
                        .toList(),
                result.routingReason(),
                result.routingConfidence(),
                escalated,
                latencyMs,
                knowledgeUsed,
                result.knowledgeSources(),
                verification.verified(),
                verification.grounded(),
                verification.issues(),
                intentResult.entities(),
                intentResult.confidence(),
                intentResult.sourceScores(),
                result.grounding() == null ? null : result.grounding().retrievalTraceId(),
                result.grounding() == null ? "not_required" : result.grounding().status(),
                result.grounding() == null ? java.util.List.of() : result.grounding().evidence()
        );
    }
}
