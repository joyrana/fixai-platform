package com.fixai.platform.fixgateway.config;

import com.fixai.platform.fixgateway.adapter.in.fix.QuickFixApplicationAdapter;
import com.fixai.platform.fixgateway.application.port.inbound.FixGatewayEventPort;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import quickfix.FileLogFactory;
import quickfix.LogFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.Session;
import quickfix.SessionFactory;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;

import static org.assertj.core.api.Assertions.assertThat;

class QuickFixConfigurationTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateQuickFixBeansAndDynamicSessionSettings() throws Exception {
        Path storeDir = Files.createDirectories(tempDir.resolve("store"));
        Path logDir = Files.createDirectories(tempDir.resolve("log"));
        String dictionaryPath = copyFix44Dictionary(tempDir.resolve("FIX44.xml"));

        FixGatewayProperties properties = new FixGatewayProperties(
                "FIXAI",
                "BROKER",
                "FIX.4.4",
                "localhost",
                9876,
                30,
                5,
                storeDir.toString(),
                logDir.toString(),
                dictionaryPath,
                true,
                false,
                true,
                true);

        QuickFixConfiguration configuration = new QuickFixConfiguration();
        SessionSettingsFactory settingsFactory = configuration.sessionSettingsFactory(properties);
        SessionID sessionId = configuration.fixSessionId(settingsFactory);
        SessionSettings sessionSettings = configuration.sessionSettings(settingsFactory);
        MessageStoreFactory messageStoreFactory = configuration.fileStoreFactory(sessionSettings);
        LogFactory logFactory = configuration.screenLogFactory(sessionSettings);
        MessageFactory messageFactory = configuration.defaultMessageFactory();
        QuickFixApplicationAdapter applicationAdapter =
                new QuickFixApplicationAdapter(Mockito.mock(FixGatewayEventPort.class));
        SocketInitiator initiator = configuration.socketInitiator(
                applicationAdapter,
                messageStoreFactory,
                sessionSettings,
                logFactory,
                messageFactory);

        assertThat(sessionId.toString()).isEqualTo("FIX.4.4:FIXAI->BROKER");
        assertThat(sessionSettings.getString(sessionId, SessionFactory.SETTING_CONNECTION_TYPE)).isEqualTo("initiator");
        assertThat(sessionSettings.getString(sessionId, FileLogFactory.SETTING_FILE_LOG_PATH)).isEqualTo(logDir.toString());
        assertThat(sessionSettings.getString(sessionId, Session.SETTING_DATA_DICTIONARY)).isEqualTo(dictionaryPath);
        assertThat(sessionSettings.getLong(sessionId, quickfix.Initiator.SETTING_SOCKET_CONNECT_PORT)).isEqualTo(9876L);
        assertThat(sessionSettings.getBool(sessionId, Session.SETTING_RESET_ON_LOGON)).isTrue();
        assertThat(sessionSettings.getBool(sessionId, Session.SETTING_RESET_ON_LOGOUT)).isFalse();
        assertThat(sessionSettings.getBool(sessionId, Session.SETTING_RESET_ON_DISCONNECT)).isTrue();
        assertThat(sessionSettings.getBool(sessionId, Session.SETTING_VALIDATE_INCOMING_MESSAGE)).isTrue();
        assertThat(initiator).isNotNull();
    }

    private String copyFix44Dictionary(Path targetPath) throws IOException {
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream("FIX44.xml")) {
            assertThat(inputStream).isNotNull();
            Files.copy(inputStream, targetPath);
            return targetPath.toString();
        }
    }
}
