package com.supportforge.persistence.postgres;

import com.supportforge.config.DatabaseProperties;
import com.supportforge.evaluation.EvalReport;
import com.supportforge.security.TenantContextProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class PostgresEvaluationReportAdapterTest {

    @Test
    void keepLatestPreservesChronologicalOrder() {
        PostgresEvaluationReportAdapter adapter = adapter(2);

        List<EvalReport> retained = adapter.keepLatest(List.of(
                report("2026-01-01T00:00:00Z"),
                report("2026-01-02T00:00:00Z"),
                report("2026-01-03T00:00:00Z")
        ));

        assertEquals(List.of(
                "2026-01-02T00:00:00Z", "2026-01-03T00:00:00Z"
        ), retained.stream().map(EvalReport::timestamp).toList());
    }

    @Test
    void rejectsInvalidHistoryLimit() {
        assertThrows(IllegalArgumentException.class, () -> adapter(0));
    }

    private static PostgresEvaluationReportAdapter adapter(int maxHistory) {
        return new PostgresEvaluationReportAdapter(
                mock(DataSource.class), mock(ObjectMapper.class),
                new TenantContextProvider("00000000-0000-0000-0000-000000000001"),
                new DatabaseProperties(
                        "", "", "", "supportforge", 2, 0,
                        Duration.ofSeconds(1), true
                ),
                maxHistory
        );
    }

    private static EvalReport report(String timestamp) {
        return new EvalReport(
                timestamp, 1, 1, 1.0, Map.of("overall", 1.0),
                List.of(), List.of(), List.of()
        );
    }
}
