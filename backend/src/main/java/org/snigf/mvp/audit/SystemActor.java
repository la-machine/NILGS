// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/**
 * Runs work that no user started (scheduled checkpoints, verification) under a named
 * service identity. Audit events recorded inside are attributed to {@code SERVICE} with
 * that label, under a fresh correlation id. The binding is a {@link ScopedValue}: it ends
 * with the task and cannot leak to other work on the same thread.
 */
public final class SystemActor {

    private static final Pattern LABEL = Pattern.compile("[a-z][a-z0-9-]{2,63}");
    private static final ScopedValue<String> CURRENT = ScopedValue.newInstance();
    /** Same MDC key as the request filter, so a log line and its audit event share the id. */
    private static final String MDC_KEY = "correlationId";

    private SystemActor() {
    }

    public static <T> T call(String label, Supplier<T> task) {
        if (label == null || !LABEL.matcher(label).matches()) {
            throw new IllegalArgumentException("service label must be lower kebab case: " + label);
        }
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, UUID.randomUUID().toString());
        try {
            return ScopedValue.where(CURRENT, label).call(task::get);
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }

    public static void run(String label, Runnable task) {
        call(label, () -> {
            task.run();
            return null;
        });
    }

    /** The service label bound to the current task, if any. */
    public static Optional<String> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }
}
