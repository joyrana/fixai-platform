package com.fixai.platform.fixgateway.config;

import quickfix.FileLogFactory;
import quickfix.FileStoreFactory;
import quickfix.Initiator;
import quickfix.ScreenLogFactory;
import quickfix.Session;
import quickfix.SessionFactory;
import quickfix.SessionID;
import quickfix.SessionSettings;

/**
 * Factory for building QuickFIX/J session settings from Spring configuration.
 */
public class SessionSettingsFactory {

    private static final String CONNECTION_TYPE_INITIATOR = "initiator";

    private final FixGatewayProperties properties;

    public SessionSettingsFactory(FixGatewayProperties properties) {
        this.properties = properties;
    }

    public SessionSettings create() {
        SessionID sessionId = createSessionId();
        SessionSettings settings = new SessionSettings();

        settings.setString(sessionId, SessionFactory.SETTING_CONNECTION_TYPE, CONNECTION_TYPE_INITIATOR);
        settings.setString(sessionId, SessionSettings.BEGINSTRING, properties.beginString());
        settings.setString(sessionId, SessionSettings.SENDERCOMPID, properties.senderCompId());
        settings.setString(sessionId, SessionSettings.TARGETCOMPID, properties.targetCompId());
        settings.setString(sessionId, Initiator.SETTING_SOCKET_CONNECT_HOST, properties.host());
        settings.setLong(sessionId, Initiator.SETTING_SOCKET_CONNECT_PORT, properties.port());
        settings.setLong(sessionId, Session.SETTING_HEARTBTINT, properties.heartbeatInterval());
        settings.setLong(sessionId, Initiator.SETTING_RECONNECT_INTERVAL, properties.reconnectInterval());
        settings.setString(sessionId, FileStoreFactory.SETTING_FILE_STORE_PATH, properties.storePath());
        settings.setString(sessionId, FileLogFactory.SETTING_FILE_LOG_PATH, properties.logPath());
        settings.setString(sessionId, Session.SETTING_DATA_DICTIONARY, properties.dictionaryPath());
        settings.setBool(sessionId, Session.SETTING_USE_DATA_DICTIONARY, true);
        settings.setBool(sessionId, Session.SETTING_NON_STOP_SESSION, true);
        settings.setBool(sessionId, Session.SETTING_RESET_ON_LOGON, properties.resetOnLogon());
        settings.setBool(sessionId, Session.SETTING_RESET_ON_LOGOUT, properties.resetOnLogout());
        settings.setBool(sessionId, Session.SETTING_RESET_ON_DISCONNECT, properties.resetOnDisconnect());
        settings.setBool(sessionId, Session.SETTING_VALIDATE_INCOMING_MESSAGE, properties.validateIncomingMessages());
        settings.setBool(sessionId, ScreenLogFactory.SETTING_LOG_INCOMING, false);
        settings.setBool(sessionId, ScreenLogFactory.SETTING_LOG_OUTGOING, false);
        settings.setBool(sessionId, ScreenLogFactory.SETTING_LOG_EVENTS, false);
        settings.setBool(sessionId, ScreenLogFactory.SETTING_LOG_HEARTBEATS, false);

        return settings;
    }

    public SessionID createSessionId() {
        return new SessionID(properties.beginString(), properties.senderCompId(), properties.targetCompId());
    }
}
