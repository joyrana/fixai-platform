package com.fixai.platform.fixcore;

import java.util.List;
import quickfix.Acceptor;
import quickfix.FileStoreFactory;
import quickfix.Initiator;
import quickfix.Session;
import quickfix.SessionFactory;
import quickfix.SessionID;
import quickfix.SessionSettings;

/**
 * Converts validated {@link FixSessionSpec}s into QuickFIX/J {@link SessionSettings}. Multiple sessions can be added
 * to one settings object; each keeps its own per-session configuration.
 */
public final class SessionSettingsBuilder {

    private final SessionSettings settings = new SessionSettings();

    public static SessionID sessionId(FixSessionSpec spec) {
        return new SessionID(spec.version().beginString(), spec.senderCompId(), spec.targetCompId());
    }

    public SessionSettingsBuilder add(FixSessionSpec spec) {
        SessionID id = sessionId(spec);
        boolean initiator = spec.role() == FixSessionSpec.Role.INITIATOR;
        settings.setString(id, SessionFactory.SETTING_CONNECTION_TYPE, initiator ? "initiator" : "acceptor");
        settings.setString(id, Session.SETTING_START_TIME, "00:00:00");
        settings.setString(id, Session.SETTING_END_TIME, "00:00:00");
        settings.setBool(id, Session.SETTING_NON_STOP_SESSION, true);
        settings.setLong(id, Session.SETTING_HEARTBTINT, spec.heartbeatIntervalSeconds());
        settings.setLong(id, Session.SETTING_LOGON_TIMEOUT, spec.logonTimeoutSeconds());
        settings.setBool(id, Session.SETTING_RESET_ON_LOGON, spec.resetOnLogon());
        settings.setBool(id, Session.SETTING_RESET_ON_LOGOUT, spec.resetOnLogout());
        settings.setBool(id, Session.SETTING_RESET_ON_DISCONNECT, spec.resetOnDisconnect());
        settings.setBool(id, Session.SETTING_VALIDATE_INCOMING_MESSAGE, spec.validateIncomingMessages());
        settings.setBool(id, Session.SETTING_USE_DATA_DICTIONARY, true);
        settings.setBool(id, Session.SETTING_PERSIST_MESSAGES, true);
        applyDictionaries(id, spec);

        if (initiator) {
            settings.setString(id, Initiator.SETTING_SOCKET_CONNECT_HOST, spec.host());
            settings.setLong(id, Initiator.SETTING_SOCKET_CONNECT_PORT, spec.port());
            settings.setLong(id, Initiator.SETTING_RECONNECT_INTERVAL, spec.reconnectIntervalSeconds());
        } else {
            settings.setLong(id, Acceptor.SETTING_SOCKET_ACCEPT_PORT, spec.port());
        }
        if (spec.storeType() == FixSessionSpec.StoreType.FILE) {
            settings.setString(id, FileStoreFactory.SETTING_FILE_STORE_PATH, spec.storePath());
        }
        return this;
    }

    public SessionSettingsBuilder addAll(List<FixSessionSpec> specs) {
        specs.forEach(this::add);
        return this;
    }

    public SessionSettings build() {
        return settings;
    }

    private void applyDictionaries(SessionID id, FixSessionSpec spec) {
        String custom = spec.customDictionaryPath();
        boolean hasCustom = custom != null && !custom.isBlank();
        if (spec.version().isFixt()) {
            settings.setString(id, Session.SETTING_TRANSPORT_DATA_DICTIONARY, spec.version().transportDictionary());
            settings.setString(id, Session.SETTING_APP_DATA_DICTIONARY,
                    hasCustom ? custom : spec.version().applicationDictionary());
            settings.setString(id, Session.SETTING_DEFAULT_APPL_VER_ID, spec.version().defaultApplVerId().orElseThrow());
        } else {
            settings.setString(id, Session.SETTING_DATA_DICTIONARY,
                    hasCustom ? custom : spec.version().applicationDictionary());
        }
    }
}
