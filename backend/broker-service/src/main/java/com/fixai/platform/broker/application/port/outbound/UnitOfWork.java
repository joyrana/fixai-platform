package com.fixai.platform.broker.application.port.outbound;

import java.util.function.Supplier;

/** Runs a state change and its audit record atomically. Remote calls must happen outside the unit of work. */
public interface UnitOfWork {

    <T> T call(Supplier<T> work);

    default void run(Runnable work) {
        call(() -> {
            work.run();
            return null;
        });
    }
}
