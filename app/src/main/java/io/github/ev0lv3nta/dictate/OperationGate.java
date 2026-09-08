package io.github.ev0lv3nta.dictate;

import java.util.concurrent.atomic.AtomicReference;

final class OperationGate {
    private static final AtomicReference<Object> OWNER = new AtomicReference<>();
    static boolean acquire(Object token) { return OWNER.compareAndSet(null, token); }
    static void release(Object token) { OWNER.compareAndSet(token, null); }
}
