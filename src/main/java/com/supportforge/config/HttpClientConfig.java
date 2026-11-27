package com.supportforge.config;

import org.springframework.context.annotation.Configuration;

/**
 * RestClient.Builder由Spring Boot自动配置，包含ObservationRegistry和追踪传播。
 * 不能用RestClient.builder()手动覆盖默认Bean，否则外部模型HTTP调用会绕开自动追踪。
 */
@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {
}
