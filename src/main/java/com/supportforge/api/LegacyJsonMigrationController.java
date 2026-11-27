package com.supportforge.api;

import com.supportforge.migration.LegacyJsonMigrationService;
import com.supportforge.migration.LegacyMigrationPreview;
import com.supportforge.migration.LegacyMigrationRequest;
import com.supportforge.migration.LegacyMigrationRun;
import com.supportforge.migration.LegacyMigrationVerification;
import com.supportforge.security.EnterpriseRole;
import com.supportforge.security.RequiresAnyRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/operations/migrations/json")
@RequiresAnyRole({EnterpriseRole.ADMIN, EnterpriseRole.AUDITOR})
@ConditionalOnProperty(
        prefix = "supportforge.features",
        name = "postgres-persistence",
        havingValue = "true"
)
public class LegacyJsonMigrationController {
    private final LegacyJsonMigrationService migrations;

    public LegacyJsonMigrationController(LegacyJsonMigrationService migrations) {
        this.migrations = migrations;
    }

    @PostMapping("/preview")
    public LegacyMigrationPreview preview(@RequestBody LegacyMigrationRequest request) {
        return migrations.preview(request);
    }

    @PostMapping("/run")
    @RequiresAnyRole(EnterpriseRole.ADMIN)
    public LegacyMigrationRun run(@RequestBody LegacyMigrationRequest request) {
        return migrations.migrate(request);
    }

    @GetMapping("/{runId}")
    public LegacyMigrationRun get(@PathVariable UUID runId) {
        return migrations.get(runId);
    }

    @PostMapping("/{runId}/verify")
    public LegacyMigrationVerification verify(@PathVariable UUID runId) {
        return migrations.verify(runId);
    }

    @PostMapping("/{runId}/rollback")
    @RequiresAnyRole(EnterpriseRole.ADMIN)
    public LegacyMigrationRun rollback(@PathVariable UUID runId) {
        return migrations.rollback(runId);
    }
}
