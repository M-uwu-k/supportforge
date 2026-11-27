package com.supportforge.api;

import com.supportforge.cache.TieredCacheService;
import com.supportforge.cache.TieredCacheStats;
import com.supportforge.observability.SloMetricsService;
import com.supportforge.observability.SloSnapshot;
import com.supportforge.resilience.DependencyResilienceGuard;
import com.supportforge.security.EnterpriseRole;
import com.supportforge.security.RequiresAnyRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/operations")
@RequiresAnyRole({EnterpriseRole.ADMIN, EnterpriseRole.AUDITOR})
public class OperationsController {
    private final SloMetricsService slo;
    private final TieredCacheService cache;
    private final DependencyResilienceGuard resilience;

    public OperationsController(
            SloMetricsService slo, TieredCacheService cache,
            DependencyResilienceGuard resilience
    ) {
        this.slo = slo;
        this.cache = cache;
        this.resilience = resilience;
    }

    @GetMapping("/slo")
    public SloSnapshot slo() {
        return slo.snapshot();
    }

    @GetMapping("/dependencies")
    public Map<String, Object> dependencies() {
        TieredCacheStats cacheStats = cache.stats();
        return Map.of(
                "cache", cacheStats,
                "valkey_circuit", resilience.snapshot("valkey")
        );
    }
}
