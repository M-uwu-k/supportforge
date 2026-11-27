package com.supportforge.resilience;

import com.supportforge.cache.TieredCacheProperties;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnProperty(
        prefix = "supportforge.features", name = "distributed-cache", havingValue = "true"
)
public class ValkeyRateLimitAdapter implements RateLimitPort {
    private static final String SCRIPT = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            if count <= tonumber(ARGV[2]) then return 1 else return 0 end
            """;
    private final RedisClient client;
    private volatile StatefulRedisConnection<String, String> connection;
    private final String namespace;

    public ValkeyRateLimitAdapter(TieredCacheProperties properties) {
        client = RedisClient.create(RedisURI.create(properties.valkeyUri()));
        namespace = properties.namespace();
    }

    @Override
    public boolean tryAcquire(String key, int maximumRequests, Duration window) {
        Long result = connection().sync().eval(
                SCRIPT, ScriptOutputType.INTEGER,
                new String[]{namespace + ":rate:" + key},
                Long.toString(window.toMillis()), Integer.toString(maximumRequests)
        );
        return result != null && result == 1;
    }

    private synchronized StatefulRedisConnection<String, String> connection() {
        if (connection == null || !connection.isOpen()) {
            connection = client.connect();
        }
        return connection;
    }

    @PreDestroy
    public void close() {
        if (connection != null) {
            connection.close();
        }
        client.shutdown();
    }
}
