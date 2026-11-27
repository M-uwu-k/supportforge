package com.supportforge.knowledge;

import com.supportforge.audit.AuditEventService;
import com.supportforge.audit.InMemoryAuditEventAdapter;
import com.supportforge.persistence.port.KnowledgeDocumentPersistencePort;
import com.supportforge.security.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeDocumentBrowseTest {
    private final UUID tenant = UUID.randomUUID();
    private final TenantContextProvider context = new TenantContextProvider(tenant.toString());
    private final KnowledgeDocument a = document("a-private", KnowledgeCategory.BILLING);
    private final KnowledgeDocument b = document("b-shared", KnowledgeCategory.TECHNICAL);
    private final KnowledgeDocument c = document("c-private", KnowledgeCategory.TECHNICAL);
    private PostgresKnowledgeBase knowledge;
    private ResourceAccessService access;

    @BeforeEach
    void setup() {
        var persistence = mock(KnowledgeDocumentPersistencePort.class);
        when(persistence.load()).thenAnswer(invocation -> context.tenantId().equals(tenant)
                ? List.of(c, a, b) : List.of());
        knowledge = new PostgresKnowledgeBase(persistence, mock(KnowledgeHybridScorer.class));
        access = new ResourceAccessService(new InMemoryResourceAclAdapter(), context,
                new AuditEventService(new InMemoryAuditEventAdapter(), context));
        knowledge.configureAccess(access);
        identity(EnterpriseRole.ADMIN, "admin");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void adminBrowsesAllCurrentTenantDocumentsInStablePages() {
        var first = knowledge.listDocuments(null, 1, 2);
        var second = knowledge.listDocuments(null, 2, 2);
        assertEquals(List.of(a, b), first.documents());
        assertEquals(List.of(c), second.documents());
        assertEquals(3, first.totalElements());
        assertEquals(2, first.totalPages());
        assertEquals(a, knowledge.getDocument(a.id()));
        assertThrows(UnsupportedOperationException.class, () -> first.documents().clear());

        TenantContext.set(new RequestIdentity(UUID.randomUUID(), "admin", Set.of(EnterpriseRole.ADMIN), "other"));
        assertEquals(0, knowledge.count());
        assertTrue(knowledge.listDocuments(null, 1, 12).documents().isEmpty());
        assertThrows(KnowledgeNotFoundException.class, () -> knowledge.getDocument(a.id()));
    }

    @Test
    void nonAdminRolesOnlyReadGrantedDocumentsAndCounts() {
        for (EnterpriseRole role : List.of(EnterpriseRole.AGENT, EnterpriseRole.AUDITOR, EnterpriseRole.KNOWLEDGE_EDITOR)) {
            identity(EnterpriseRole.ADMIN, "admin");
            access.grant("knowledge_document", b.id(), role.name(), AclPermission.READ);
            identity(role, role.name());
            var page = knowledge.listDocuments(null, 1, 1);
            assertEquals(List.of(b), page.documents());
            assertEquals(1, page.totalElements());
            assertEquals(1, page.totalPages());
            assertEquals(1, knowledge.count());
            assertEquals(b, knowledge.getDocument(b.id()));
            assertThrows(KnowledgeNotFoundException.class, () -> knowledge.getDocument(a.id()));
            assertTrue(knowledge.listDocuments(null, 2, 1).documents().isEmpty());
        }
    }

    @Test
    void ungrantedAccountHasEmptyBrowseAndCannotReadDetails() {
        identity(EnterpriseRole.AGENT, "ungranted");
        assertEquals(0, knowledge.listDocuments(null, 1, 12).totalElements());
        assertEquals(0, knowledge.count());
        assertThrows(KnowledgeNotFoundException.class, () -> knowledge.getDocument(b.id()));
    }

    @Test
    void categoryFiltersBeforePageCountsAndRejectsInvalidPagination() {
        var page = knowledge.listDocuments(KnowledgeCategory.TECHNICAL, 1, 1);
        assertEquals(List.of(b), page.documents());
        assertEquals(2, page.totalElements());
        assertEquals(2, page.totalPages());
        assertThrows(IllegalArgumentException.class, () -> knowledge.listDocuments(null, 0, 12));
        assertThrows(IllegalArgumentException.class, () -> knowledge.listDocuments(null, 1, 101));
        assertThrows(IllegalArgumentException.class, () -> knowledge.listDocuments(null, 1, 0));
        assertTrue(knowledge.listDocuments(null, Integer.MAX_VALUE, 100).documents().isEmpty());
        assertThrows(KnowledgeNotFoundException.class, () -> knowledge.getDocument("missing"));
    }

    private void identity(EnterpriseRole role, String actor) {
        TenantContext.set(new RequestIdentity(tenant, actor, Set.of(role), "browse-test"));
    }

    private static KnowledgeDocument document(String id, KnowledgeCategory category) {
        return new KnowledgeDocument(id, id, category, "完整正文\n包含全部步骤", List.of("测试"));
    }
}
