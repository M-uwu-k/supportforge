package com.supportforge.cache;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** Valkey使用Redis RESP兼容协议；Lettuce连接在组件销毁时显式关闭。 */
@Component
@ConditionalOnProperty(
        prefix = "supportforge.features", name = "distributed-cache",
        havingValue = "true"
)
public class ValkeyDistributedCacheAdapter implements DistributedCachePort {

    private final RedisClient client;
    private volatile StatefulRedisConnection<String, String> connection;

    public ValkeyDistributedCacheAdapter(TieredCacheProperties properties) {
        this.client = RedisClient.create(RedisURI.create(properties.valkeyUri()));
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(commands().get(key));
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        commands().setex(key, Math.max(1, ttl.toSeconds()), value);
    }

    @Override
    public void delete(String key) {
        commands().del(key);
    }

    private synchronized RedisCommands<String, String> commands() {
        if (connection == null || !connection.isOpen()) {
            connection = client.connect();
        }
        return connection.sync();
    }

    @PreDestroy
    public void close() {
        if (connection != null) {
            connection.close();
        }
        client.shutdown();
    }
}
