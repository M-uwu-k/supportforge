package com.supportforge.tool;

import com.supportforge.knowledge.*;
import com.supportforge.security.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeToolCacheAccessTest {
    @Test
    void cachedKnowledgeIsRecheckedAndFailsClosedWhenTheAclStoreIsUnavailable() {
        var tool = new KnowledgeSearchTool(new MultiQueryKnowledgeRetriever(
                new InMemoryKnowledgeBase(), new KnowledgeQueryRewriter(4), Runnable::run));
        ResourceAccessService access = mock(ResourceAccessService.class);
        AtomicBoolean allowed = new AtomicBoolean(true);
        when(access.allowed(anyString(), anyString(), eq(AclPermission.READ))).thenAnswer(call -> allowed.get());
        tool.configureAccess(access);
        var identity = new RequestIdentity(UUID.randomUUID(), "reader", Set.of(EnterpriseRole.AGENT), "r1");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var manager = new ToolManager(List.of(tool), executor);
            var parameters = Map.<String, Object>of("query", "登录报错401", "limit", 3);
            ToolResult original = TenantContext.callWith(identity, () -> manager.call("knowledge_search", parameters, null));
            assertThat(original.success()).isTrue();
            assertThat((List<?>) original.data()).isNotEmpty();
            allowed.set(false);
            ToolResult restricted = TenantContext.callWith(identity, () -> manager.call("knowledge_search", parameters, null));
            assertThat(restricted.cached()).isTrue();
            assertThat((List<?>) restricted.data()).isEmpty();
            when(access.allowed(anyString(), anyString(), eq(AclPermission.READ)))
                    .thenThrow(new IllegalStateException("ACL unavailable"));
            ToolResult unavailable = TenantContext.callWith(identity, () -> manager.call("knowledge_search", parameters, null));
            assertThat(unavailable.success()).isFalse();
            assertThat(unavailable.data()).isNull();
        }
    }
}
