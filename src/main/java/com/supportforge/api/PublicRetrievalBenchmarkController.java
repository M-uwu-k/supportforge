package com.supportforge.api;

import com.supportforge.publicbenchmark.PublicBenchmarkImportRequest;
import com.supportforge.publicbenchmark.PublicBenchmarkImportResult;
import com.supportforge.publicbenchmark.PublicBenchmarkReport;
import com.supportforge.publicbenchmark.PublicBenchmarkRunRequest;
import com.supportforge.publicbenchmark.PublicBenchmarkStatus;
import com.supportforge.publicbenchmark.PublicRetrievalBenchmarkService;
import com.supportforge.security.EnterpriseRole;
import com.supportforge.security.RequiresAnyRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 隔离公开数据集的管理、导入和正式评测入口。 */
@RestController
@RequestMapping("/quality/public-retrieval-benchmarks")
@RequiresAnyRole({EnterpriseRole.ADMIN, EnterpriseRole.AUDITOR})
@ConditionalOnProperty(
        prefix = "supportforge.qdrant",
        name = "provider",
        havingValue = "qdrant"
)
public class PublicRetrievalBenchmarkController {
    private final PublicRetrievalBenchmarkService benchmarks;

    public PublicRetrievalBenchmarkController(
            PublicRetrievalBenchmarkService benchmarks
    ) {
        this.benchmarks = benchmarks;
    }

    @PostMapping("/ensure")
    @RequiresAnyRole(EnterpriseRole.ADMIN)
    public PublicBenchmarkStatus ensure() {
        return benchmarks.ensureReady();
    }

    @GetMapping("/status")
    public PublicBenchmarkStatus status() {
        return benchmarks.status();
    }

    @PostMapping("/corpus/import")
    @RequiresAnyRole(EnterpriseRole.ADMIN)
    public PublicBenchmarkImportResult importCorpus(
            @RequestBody PublicBenchmarkImportRequest request
    ) {
        return benchmarks.importBatch(request);
    }

    @PostMapping("/run")
    public PublicBenchmarkReport run(
            @RequestBody PublicBenchmarkRunRequest request
    ) {
        return benchmarks.evaluate(request);
    }
}

