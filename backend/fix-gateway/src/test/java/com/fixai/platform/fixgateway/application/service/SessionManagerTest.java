package com.fixai.platform.fixgateway.application.service;

import com.fixai.platform.fixgateway.application.port.outbound.FixSessionLookupPort;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import quickfix.ConfigError;
import quickfix.Initiator;
import quickfix.Session;
import quickfix.SessionID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionManagerTest {

    @Mock
    private Initiator initiator;

    @Mock
    private FixSessionLookupPort fixSessionLookupPort;

    @Mock
    private Session session;

    private SessionManager sessionManager;
    private SessionID sessionId;

    @BeforeEach
    void setUp() {
        sessionId = new SessionID("FIX.4.4", "SENDER", "TARGET");
        sessionManager = new SessionManager(initiator, sessionId, fixSessionLookupPort);
    }

    @Test
    void shouldStartAndStopInitiator() throws Exception {
        sessionManager.start();
        sessionManager.stop();

        verify(initiator).start();
        verify(initiator).stop();
    }

    @Test
    void shouldWrapQuickFixStartupFailures() throws Exception {
        doThrow(new ConfigError("bad config")).when(initiator).start();

        assertThatThrownBy(() -> sessionManager.start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to start FIX initiator");
    }

    @Test
    void shouldResolveCurrentSession() {
        when(fixSessionLookupPort.find(sessionId)).thenReturn(Optional.of(session));

        assertThat(sessionManager.getSession()).contains(session);
    }

    @Test
    void shouldReportLoggedOnOnlyWhenInitiatorAndSessionAreBothActive() {
        when(initiator.isLoggedOn()).thenReturn(true);
        when(fixSessionLookupPort.find(sessionId)).thenReturn(Optional.of(session));
        when(session.isLoggedOn()).thenReturn(true);

        assertThat(sessionManager.isLoggedOn()).isTrue();
    }

    @Test
    void shouldReportNotLoggedOnWhenSessionCannotBeResolved() {
        when(initiator.isLoggedOn()).thenReturn(true);
        when(fixSessionLookupPort.find(sessionId)).thenReturn(Optional.empty());

        assertThat(sessionManager.isLoggedOn()).isFalse();
    }
}
