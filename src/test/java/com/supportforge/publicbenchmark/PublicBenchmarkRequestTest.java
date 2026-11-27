package com.supportforge.publicbenchmark;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicBenchmarkRequestTest {

    @Test
    void defaultsToStandardPublicRetrievalDepths() {
        PublicBenchmarkRunRequest request = new PublicBenchmarkRunRequest(
                "dataset", "revision", null, null, null,
                List.of(new PublicBenchmarkQuery(
                        "q1", "手机壳", Map.of("p1", 1)
                ))
        );

        assertThat(request.k()).isEqualTo(10);
        assertThat(request.candidateK()).isEqualTo(100);
        assertThat(request.requiresRealProviders()).isTrue();
    }

    @Test
    void preservesGradedQrelsAndRejectsInvalidScores() {
        PublicBenchmarkQuery query = new PublicBenchmarkQuery(
                "q1", "退款", Map.of("p1", 3, "p2", 1)
        );
        assertThat(query.relevanceJudgments()).containsEntry("p1", 3);
        assertThatThrownBy(() -> new PublicBenchmarkQuery(
                "q2", "退款", Map.of("p1", 0)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("相关度");
    }
}

