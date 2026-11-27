package com.supportforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;


@ConfigurationProperties(prefix = "supportforge.database")
public record DatabaseProperties(
        String url,
        String username,
        String password,
        String schema,
        int maximumPoolSize,
        int minimumIdle,
        Duration connectionTimeout,
        boolean migrationEnabled
) {


    public DatabaseProperties {
        url = normalize(url);
        username = normalize(username);
        password = password == null ? "" : password;
        schema = normalize(schema);

        if (schema.isEmpty()) {
            schema = "supportforge";
        }

        if (maximumPoolSize == 0) {
            maximumPoolSize = 10;
        } else if (maximumPoolSize < 0) {
            throw new IllegalArgumentException("数据库连接池最大连接数必须至少为1");
        }

        if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw new IllegalArgumentException("数据库最小空闲连接数必须在0到最大连接数之间");
        }
        if (connectionTimeout == null) {
            connectionTimeout = Duration.ofSeconds(5);
        } else if (connectionTimeout.compareTo(Duration.ofMillis(250)) < 0) {
            throw new IllegalArgumentException("数据库连接超时不能小于250毫秒");
        }
    }


    public void validateForConnection() {
        if (url.isEmpty()) {
            throw new IllegalStateException(
                    "已启用PostgreSQL，但SUPPORTFORGE_POSTGRES_URL未配置"
            );
        }
        if (!url.startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException(
                    "PostgreSQL连接地址必须以jdbc:postgresql://开头"
            );
        }
        if (username.isEmpty()) {
            throw new IllegalStateException(
                    "已启用PostgreSQL，但SUPPORTFORGE_POSTGRES_USERNAME未配置"
            );
        }
    }


    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}

