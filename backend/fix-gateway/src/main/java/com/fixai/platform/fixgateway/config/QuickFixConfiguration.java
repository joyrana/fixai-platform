package com.fixai.platform.fixgateway.config;

import com.fixai.platform.fixgateway.adapter.in.fix.QuickFixApplicationAdapter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.FileStoreFactory;
import quickfix.LogFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.ScreenLogFactory;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;

/**
 * Spring configuration for QuickFIX/J gateway infrastructure beans.
 */
@Configuration
@EnableConfigurationProperties(FixGatewayProperties.class)
public class QuickFixConfiguration {

    @Bean
    public SessionSettingsFactory sessionSettingsFactory(FixGatewayProperties properties) {
        return new SessionSettingsFactory(properties);
    }

    @Bean
    public SessionID fixSessionId(SessionSettingsFactory sessionSettingsFactory) {
        return sessionSettingsFactory.createSessionId();
    }

    @Bean
    public SessionSettings sessionSettings(SessionSettingsFactory sessionSettingsFactory) {
        return sessionSettingsFactory.create();
    }

    @Bean
    public MessageStoreFactory fileStoreFactory(SessionSettings sessionSettings) {
        return new FileStoreFactory(sessionSettings);
    }

    @Bean
    public LogFactory screenLogFactory(SessionSettings sessionSettings) {
        return new ScreenLogFactory(sessionSettings);
    }

    @Bean
    public MessageFactory defaultMessageFactory() {
        return new DefaultMessageFactory();
    }

    @Bean
    public SocketInitiator socketInitiator(
            QuickFixApplicationAdapter application,
            MessageStoreFactory messageStoreFactory,
            SessionSettings sessionSettings,
            LogFactory logFactory,
            MessageFactory messageFactory) throws ConfigError {
        return new SocketInitiator(application, messageStoreFactory, sessionSettings, logFactory, messageFactory);
    }
}
