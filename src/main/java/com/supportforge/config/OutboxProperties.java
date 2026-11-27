package com.supportforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Transactional Outbox领取、租约和退避参数。 */
@ConfigurationProperties(prefix = "supportforge.outbox")
public record OutboxProperties(
        int claimBatchSize,
        Duration leaseDuration,
        int maximumAttempts,
        Duration initialRetryDelay,
        Duration maximumRetryDelay
) {

    public OutboxProperties {
        claimBatchSize = claimBatchSize == 0 ? 50 : claimBatchSize;
        leaseDuration = leaseDuration == null
                ? Duration.ofSeconds(30) : leaseDuration;
        maximumAttempts = maximumAttempts == 0 ? 10 : maximumAttempts;
        initialRetryDelay = initialRetryDelay == null
                ? Duration.ofSeconds(1) : initialRetryDelay;
        maximumRetryDelay = maximumRetryDelay == null
                ? Duration.ofMinutes(5) : maximumRetryDelay;

        if (claimBatchSize < 1 || claimBatchSize > 1_000) {
            throw new IllegalArgumentException("Outbox领取批大小必须在1到1000之间");
        }
        if (maximumAttempts < 1 || maximumAttempts > 1_000) {
            throw new IllegalArgumentException("Outbox最大尝试次数必须在1到1000之间");
        }
        if (leaseDuration.isZero() || leaseDuration.isNegative()
                || initialRetryDelay.isZero() || initialRetryDelay.isNegative()
                || maximumRetryDelay.isZero() || maximumRetryDelay.isNegative()
                || maximumRetryDelay.compareTo(initialRetryDelay) < 0) {
            throw new IllegalArgumentException("Outbox租约/重试时间配置非法");
        }
    }

    public Duration retryDelayForAttempt(int attemptCount) {
        if (attemptCount < 1) {
            throw new IllegalArgumentException("尝试次数必须至少为1");
        }
        long multiplier = 1L << Math.min(attemptCount - 1, 30);
        Duration candidate;
        try {
            candidate = initialRetryDelay.multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            return maximumRetryDelay;
        }
        return candidate.compareTo(maximumRetryDelay) > 0
                ? maximumRetryDelay : candidate;
    }
}
