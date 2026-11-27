package com.supportforge.trace;

import com.supportforge.config.EmbeddingProperties;
import com.supportforge.config.RerankerProperties;
import com.supportforge.grounding.GroundingDecision;
import com.supportforge.grounding.GroundingHarness;
import com.supportforge.grounding.GroundingRefusalReason;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class TraceableGroundingServiceTest {

    @Test
    void keepsRawQuestionWhileRetrievingWithHistoryResolvedQuestion() {
        GroundingHarness harness = mock(GroundingHarness.class);
        when(harness.ground(any(UUID.class), any(), anySet())).thenReturn(new GroundingDecision(
                false, GroundingRefusalReason.NO_EVIDENCE, "refuse", 0, List.of(), ""));
        AtomicReference<RetrievalTrace> saved = new AtomicReference<>();
        RetrievalTracePort port = new RetrievalTracePort() {
            public void save(RetrievalTrace trace) { saved.set(trace); }
            public java.util.Optional<RetrievalTrace> find(UUID tenantId, UUID traceId) { return java.util.Optional.empty(); }
        };
        var service = new TraceableGroundingService(harness, port, embedding(), reranker());
        UUID tenant = UUID.randomUUID();
        service.ground(tenant, "那先做什么？", "杉鹭装置灯不亮时先做什么？", Set.of());
        verify(harness).ground(tenant, "杉鹭装置灯不亮时先做什么？", Set.of());
        assertEquals("那先做什么？", saved.get().originalQuery());
        assertEquals("杉鹭装置灯不亮时先做什么？", saved.get().filters().get("retrieval_query"));
    }

    @Test
    void attachesStableTraceIdAndPersistsRefusalMetadata() {
        GroundingHarness harness = mock(GroundingHarness.class);
        when(harness.ground(any(UUID.class), any(), anySet())).thenReturn(
                new GroundingDecision(
                        false, GroundingRefusalReason.LOW_RELEVANCE,
                        "refuse", 0.1, List.of(), ""
                )
        );
        AtomicReference<RetrievalTrace> saved = new AtomicReference<>();
        RetrievalTracePort port = new RetrievalTracePort() {
            @Override
            public void save(RetrievalTrace trace) {
                saved.set(trace);
            }

            @Override
            public java.util.Optional<RetrievalTrace> find(UUID tenantId, UUID traceId) {
                return java.util.Optional.empty();
            }
        };
        TraceableGroundingService service = new TraceableGroundingService(
                harness, port, embedding(), reranker()
        );
        UUID tenant = UUID.randomUUID();

        GroundingDecision result = service.ground(
                tenant, "unsupported", Set.of("BILLING")
        );

        assertNotNull(result.traceId());
        assertEquals(result.traceId(), saved.get().id());
        assertEquals("LOW_RELEVANCE", saved.get().filters().get("refusal_reason"));
        assertEquals("embedding@embed-v1", saved.get().embeddingModel());
        assertEquals(tenant, saved.get().tenantId());
    }

    private EmbeddingProperties embedding() {
        return new EmbeddingProperties(
                "mock", "embedding", "embed-v1", "http://localhost", "",
                4, 10, Duration.ofSeconds(1), true, "instruction"
        );
    }

    private RerankerProperties reranker() {
        return new RerankerProperties(
                "mock", "reranker", "rank-v1", "http://localhost", "",
                10, 10, 0.0, Duration.ofSeconds(1), true
        );
    }
}
