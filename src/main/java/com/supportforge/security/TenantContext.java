package com.supportforge.security;

import java.util.Optional;

public final class TenantContext {
    private static final ThreadLocal<RequestIdentity> CURRENT = new ThreadLocal<>();

    private TenantContext() { }

    public static void set(RequestIdentity identity) {
        CURRENT.set(identity);
    }

    public static Optional<RequestIdentity> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 在线程切换时显式传递身份，结束后恢复旧上下文，避免线程复用或同线程执行器串租户。 */
    public static <T> T callWith(RequestIdentity identity, java.util.function.Supplier<T> action) {
        RequestIdentity previous = CURRENT.get();
        if (identity == null) CURRENT.remove(); else CURRENT.set(identity);
        try {
            return action.get();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
