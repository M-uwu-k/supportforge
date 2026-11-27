package com.supportforge.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.Set;

public class RoleAuthorizationInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler
    ) throws Exception {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequiresAnyRole requirement = method.getMethodAnnotation(RequiresAnyRole.class);
        if (requirement == null) {
            requirement = method.getBeanType().getAnnotation(RequiresAnyRole.class);
        }
        if (requirement == null) {

            var identity = TenantContext.current().orElse(null);
            if (request.getMethod() != null && Set.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod())
                    && identity != null && !identity.hasAny(Set.of(EnterpriseRole.ADMIN,
                    EnterpriseRole.AGENT, EnterpriseRole.KNOWLEDGE_EDITOR))) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "只读角色不能修改业务数据");
                return false;
            }
            return true;
        }
        RequestIdentity identity = TenantContext.current().orElse(null);
        Set<EnterpriseRole> expected = Set.copyOf(Arrays.asList(requirement.value()));
        if (identity == null || !identity.hasAny(expected)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "当前角色没有访问权限");
            return false;
        }
        return true;
    }
}
