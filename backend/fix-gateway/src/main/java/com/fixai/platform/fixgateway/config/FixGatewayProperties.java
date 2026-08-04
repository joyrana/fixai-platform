package com.fixai.platform.fixgateway.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the FIX gateway session.
 *
 * @param senderCompId sender CompID used by this gateway
 * @param targetCompId target CompID used by the counterparty
 * @param beginString FIX begin string for the session
 * @param host target FIX host
 * @param port target FIX port
 * @param heartbeatInterval heartbeat interval in seconds
 * @param reconnectInterval reconnect interval in seconds
 * @param storePath file store path for QuickFIX/J message persistence
 * @param logPath log path reserved in the generated session settings
 * @param dictionaryPath filesystem path to the FIX data dictionary
 * @param resetOnLogon whether sequence numbers reset on logon
 * @param resetOnLogout whether sequence numbers reset on logout
 * @param resetOnDisconnect whether sequence numbers reset on disconnect
 * @param validateIncomingMessages whether inbound messages are validated
 */
@Validated
@ConfigurationProperties(prefix = "fix.gateway")
public record FixGatewayProperties(
        @NotBlank String senderCompId,
        @NotBlank String targetCompId,
        @NotBlank String beginString,
        @NotBlank String host,
        @Min(1) int port,
        @Min(1) int heartbeatInterval,
        @Min(1) int reconnectInterval,
        @NotBlank String storePath,
        @NotBlank String logPath,
        @NotBlank String dictionaryPath,
        boolean resetOnLogon,
        boolean resetOnLogout,
        boolean resetOnDisconnect,
        boolean validateIncomingMessages) {
}
