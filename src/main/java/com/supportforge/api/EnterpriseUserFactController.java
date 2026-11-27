package com.supportforge.api;

import com.supportforge.memory.fact.UserFact;
import com.supportforge.memory.fact.UserFactService;
import com.supportforge.memory.fact.UserFactSource;
import jakarta.validation.Valid;
import com.supportforge.security.EnterpriseRole;
import com.supportforge.security.RequiresAnyRole;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/memory/users/{userId}/facts")
public class EnterpriseUserFactController {

    private final UserFactService service;

    public EnterpriseUserFactController(UserFactService service) {
        this.service = service;
    }

    @PostMapping("/{factKey}")
    @RequiresAnyRole({EnterpriseRole.ADMIN, EnterpriseRole.AGENT})
    @ResponseStatus(HttpStatus.CREATED)
    public UserFact put(
            @PathVariable String userId,
            @PathVariable String factKey,
            @Valid @RequestBody FactRequest request
    ) {
        return service.put(
                userId, factKey, request.value(), request.source(),
                request.sourceReference(), request.confidence(), request.expiresAt()
        );
    }

    @PutMapping("/{factKey}/correction")
    @RequiresAnyRole({EnterpriseRole.ADMIN, EnterpriseRole.AGENT})
    public UserFact correct(
            @PathVariable String userId,
            @PathVariable String factKey,
            @Valid @RequestBody CorrectionRequest request
    ) {
        return service.correct(userId, factKey, request.value(), request.sourceReference());
    }

    @GetMapping
    @RequiresAnyRole({EnterpriseRole.ADMIN, EnterpriseRole.AGENT, EnterpriseRole.AUDITOR})
    public List<UserFact> active(@PathVariable String userId) {
        return service.active(userId);
    }

    @DeleteMapping("/{factKey}")
    @RequiresAnyRole({EnterpriseRole.ADMIN})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String userId, @PathVariable String factKey) {
        service.revoke(userId, factKey);
    }

    public record FactRequest(
            @NotBlank String value,
            @NotNull UserFactSource source,
            String sourceReference,
            @Min(0) @Max(1) double confidence,
            Instant expiresAt
    ) { }

    public record CorrectionRequest(
            @NotBlank String value,
            @NotBlank String sourceReference
    ) { }
}
