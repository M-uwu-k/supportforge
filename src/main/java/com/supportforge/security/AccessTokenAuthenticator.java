package com.supportforge.security;

import com.supportforge.config.FeatureFlagProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 局域网访问凭证：服务器只保存随机令牌的SHA-256摘要，身份/角色由服务器配置决定。 */
@Component
public class AccessTokenAuthenticator {
    private final boolean required;
    private final Map<String, Credential> credentials;

    public AccessTokenAuthenticator(@Value("${supportforge.auth.required:false}") boolean required,
            @Value("${supportforge.auth.credentials-json:[]}") String json,
            ObjectMapper mapper, FeatureFlagProperties features) {
        this.required = required;
        Map<String, Credential> entries = new LinkedHashMap<>();
        for (Credential credential : mapper.readValue(json, Credential[].class)) {
            if (credential.tokenSha256() == null || !credential.tokenSha256().matches("[a-f0-9]{64}")
                    || credential.tenantId() == null || credential.actorId() == null || credential.actorId().isBlank()
                    || credential.roles() == null || credential.roles().isEmpty()
                    || entries.putIfAbsent(credential.tokenSha256(), credential) != null) {
                throw new IllegalArgumentException("访问凭证配置无效或重复");
            }
        }
        if (required && (!features.multiTenancy() || entries.isEmpty())) {
            throw new IllegalArgumentException("启用访问认证时必须启用多租户并配置访问凭证，请先运行Initialize-LanAccess.ps1");
        }
        this.credentials = Map.copyOf(entries);
    }

    public boolean required() { return required; }

    public RequestIdentity authenticate(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) throw unauthorized();
        String token = header.substring(7).strip();
        if (token.isEmpty() || token.length() > 512) throw unauthorized();
        Credential credential = credentials.get(sha256(token));
        if (credential == null || (credential.metricsOnly()
                && !request.getRequestURI().equals("/actuator/prometheus"))) throw unauthorized();
        String requestId = request.getHeader("X-Request-Id");
        return new RequestIdentity(credential.tenantId(), credential.actorId(), credential.roles(),
                requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId);
    }

    private static IllegalArgumentException unauthorized() {
        return new IllegalArgumentException("缺少或无效的访问令牌，请在企业设置中填写管理员发放的凭证");
    }

    private static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record Credential(String tokenSha256, UUID tenantId, String actorId,
                             Set<EnterpriseRole> roles, boolean metricsOnly) {}
}
