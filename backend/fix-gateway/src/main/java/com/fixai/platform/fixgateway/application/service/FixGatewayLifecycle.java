package com.fixai.platform.fixgateway.application.service;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Spring lifecycle bridge that starts and stops the FIX gateway with the application context.
 */
@Component
public class FixGatewayLifecycle implements SmartLifecycle {

    private final SessionManager sessionManager;
    private final AtomicBoolean running;

    public FixGatewayLifecycle(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
        this.running = new AtomicBoolean(false);
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            sessionManager.start();
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            sessionManager.stop();
        }
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
