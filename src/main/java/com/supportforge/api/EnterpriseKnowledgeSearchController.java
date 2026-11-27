package com.supportforge.api;

import com.supportforge.reranker.EnterpriseRerankingService;
import com.supportforge.reranker.RerankedSearchHit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 企业检索验收入口；第20步会把默认租户替换为认证后的请求租户上下文。 */
@RestController
@RequestMapping("/knowledge/enterprise")
@ConditionalOnProperty(
        prefix = "supportforge.features",
        name = "qdrant-retrieval",
        havingValue = "true"
)
public class EnterpriseKnowledgeSearchController {

    private final EnterpriseRerankingService rerankingService;
    private final com.supportforge.security.TenantContextProvider tenants;

    public EnterpriseKnowledgeSearchController(
            EnterpriseRerankingService rerankingService,
            com.supportforge.security.TenantContextProvider tenants
    ) {
        this.rerankingService = rerankingService;
        this.tenants = tenants;
    }

    @GetMapping("/search")
    public List<RerankedSearchHit> search(
            @RequestParam String query,
            @RequestParam(required = false) Set<String> category,
            @RequestParam(required = false) Integer limit
    ) {
        return rerankingService.search(tenants.tenantId(), query, category, limit);
    }
}
