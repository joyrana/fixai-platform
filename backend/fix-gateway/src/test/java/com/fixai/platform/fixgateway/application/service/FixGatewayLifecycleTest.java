package com.fixai.platform.fixgateway.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FixGatewayLifecycleTest {

    @Mock
    private SessionManager sessionManager;

    private FixGatewayLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        lifecycle = new FixGatewayLifecycle(sessionManager);
    }

    @Test
    void shouldAutoStartAndStopGatewayOnlyOnce() {
        lifecycle.start();
        lifecycle.start();

        assertThat(lifecycle.isAutoStartup()).isTrue();
        assertThat(lifecycle.isRunning()).isTrue();
        verify(sessionManager, times(1)).start();

        lifecycle.stop();
        lifecycle.stop();

        assertThat(lifecycle.isRunning()).isFalse();
        verify(sessionManager, times(1)).stop();
    }

    @Test
    void shouldInvokeStopCallback() {
        final boolean[] invoked = {false};

        lifecycle.start();
        lifecycle.stop(() -> invoked[0] = true);

        assertThat(invoked[0]).isTrue();
    }
}
