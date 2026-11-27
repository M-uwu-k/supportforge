package com.supportforge.api;

import com.supportforge.quality.QualityGateRequest;
import com.supportforge.quality.QualityGateResult;
import com.supportforge.quality.QualityGateService;
import com.supportforge.security.EnterpriseRole;
import com.supportforge.security.RequiresAnyRole;
import com.supportforge.security.TenantContextProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/quality/gates")
@RequiresAnyRole({EnterpriseRole.ADMIN, EnterpriseRole.AUDITOR})
public class QualityGateController {
    private final QualityGateService gates;
    private final TenantContextProvider tenants;

    public QualityGateController(QualityGateService gates, TenantContextProvider tenants) {
        this.gates = gates;
        this.tenants = tenants;
    }

    @PostMapping("/run")
    public QualityGateResult run(@RequestBody QualityGateRequest request) {
        return gates.evaluate(tenants.tenantId(), request.cases());
    }
}
