package com.fixai.platform.fixcore;

import java.util.Objects;

/**
 * Protocol-level description of one FIX session, independent of any persistence or web model.
 *
 * @param role whether this side connects (initiator) or listens (acceptor)
 * @param version FIX version
 * @param senderCompId our CompID
 * @param targetCompId counterparty CompID
 * @param host counterparty host (initiator only)
 * @param port counterparty port (initiator) or listen port (acceptor)
 * @param heartbeatIntervalSeconds HeartBtInt(108)
 * @param reconnectIntervalSeconds initiator reconnect back-off
 * @param logonTimeoutSeconds time allowed for the Logon handshake
 * @param resetOnLogon send ResetSeqNumFlag(141)=Y on Logon
 * @param resetOnLogout reset sequence numbers after Logout
 * @param resetOnDisconnect reset sequence numbers after disconnect
 * @param validateIncomingMessages validate inbound messages against the dictionary
 * @param storeType message store implementation
 * @param storePath directory for {@link StoreType#FILE}; ignored otherwise
 * @param customDictionaryPath optional broker-specific dictionary; bundled dictionary when {@code null}
 */
public record FixSessionSpec(
        Role role,
        FixVersion version,
        String senderCompId,
        String targetCompId,
        String host,
        int port,
        int heartbeatIntervalSeconds,
        int reconnectIntervalSeconds,
        int logonTimeoutSeconds,
        boolean resetOnLogon,
        boolean resetOnLogout,
        boolean resetOnDisconnect,
        boolean validateIncomingMessages,
        StoreType storeType,
        String storePath,
        String customDictionaryPath) {

    public enum Role {
        INITIATOR,
        ACCEPTOR
    }

    public enum StoreType {
        MEMORY,
        FILE
    }

    public FixSessionSpec {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(storeType, "storeType");
    }

    /** Defaults suitable for a short-lived certification session against the given endpoint. */
    public static FixSessionSpec initiator(FixVersion version, String sender, String target, String host, int port) {
        return new FixSessionSpec(Role.INITIATOR, version, sender, target, host, port,
                30, 5, 10, true, false, false, true, StoreType.MEMORY, null, null);
    }

    public FixSessionSpec withHeartbeat(int seconds) {
        return new FixSessionSpec(role, version, senderCompId, targetCompId, host, port, seconds,
                reconnectIntervalSeconds, logonTimeoutSeconds, resetOnLogon, resetOnLogout, resetOnDisconnect,
                validateIncomingMessages, storeType, storePath, customDictionaryPath);
    }

    public FixSessionSpec withCompIds(String sender, String target) {
        return new FixSessionSpec(role, version, sender, target, host, port, heartbeatIntervalSeconds,
                reconnectIntervalSeconds, logonTimeoutSeconds, resetOnLogon, resetOnLogout, resetOnDisconnect,
                validateIncomingMessages, storeType, storePath, customDictionaryPath);
    }

    public FixSessionSpec withResetOnLogon(boolean reset) {
        return new FixSessionSpec(role, version, senderCompId, targetCompId, host, port, heartbeatIntervalSeconds,
                reconnectIntervalSeconds, logonTimeoutSeconds, reset, resetOnLogout, resetOnDisconnect,
                validateIncomingMessages, storeType, storePath, customDictionaryPath);
    }
}
