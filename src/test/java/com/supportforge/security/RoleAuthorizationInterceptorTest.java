package com.supportforge.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleAuthorizationInterceptorTest {
    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void allowsExpectedRoleAndRejectsUnexpectedRole() throws Exception {
        RoleAuthorizationInterceptor interceptor = new RoleAuthorizationInterceptor();
        HandlerMethod method = new HandlerMethod(new SecuredHandler(), "adminOnly");

        TenantContext.set(new RequestIdentity(
                UUID.randomUUID(), "actor", Set.of(EnterpriseRole.ADMIN), "request"
        ));
        assertTrue(interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), method
        ));

        TenantContext.set(new RequestIdentity(
                UUID.randomUUID(), "actor", Set.of(EnterpriseRole.AGENT), "request"
        ));
        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(new MockHttpServletRequest(), denied, method));
        assertEquals(403, denied.getStatus());
    }

    static class SecuredHandler {
        @RequiresAnyRole({EnterpriseRole.ADMIN})
        public void adminOnly() { }
    }
}
