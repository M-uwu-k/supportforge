package com.supportforge.api;

import com.supportforge.grounding.GroundingDecision;
import com.supportforge.trace.RetrievalTrace;
import com.supportforge.trace.RetrievalTracePort;
import com.supportforge.trace.TraceableGroundingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

/** Grounding策略验收入口；只有Qdrant和Harness两个开关都开启时注册。 */
@RestController
@RequestMapping("/knowledge/enterprise")
@ConditionalOnProperty(
        prefix = "supportforge.features",
        name = {"qdrant-retrieval", "grounding-harness"},
        havingValue = "true"
)
public class EnterpriseGroundingController {

    private final TraceableGroundingService grounding;
    private final RetrievalTracePort traces;
    private final com.supportforge.security.TenantContextProvider tenants;

    public EnterpriseGroundingController(
            TraceableGroundingService grounding,
            RetrievalTracePort traces,
            com.supportforge.security.TenantContextProvider tenants
    ) {
        this.grounding = grounding;
        this.traces = traces;
        this.tenants = tenants;
    }

    @GetMapping("/ground")
    public GroundingDecision ground(
            @RequestParam String query,
            @RequestParam(required = false) Set<String> category
    ) {
        return grounding.ground(tenants.tenantId(), query, category);
    }

    @GetMapping("/traces/{traceId}")
    public RetrievalTrace trace(@PathVariable UUID traceId) {
        return traces.find(tenants.tenantId(), traceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "检索轨迹不存在"
                ));
    }
}
