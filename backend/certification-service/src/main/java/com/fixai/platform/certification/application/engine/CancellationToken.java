package com.fixai.platform.certification.application.engine;

import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation flag checked between and during steps. */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
