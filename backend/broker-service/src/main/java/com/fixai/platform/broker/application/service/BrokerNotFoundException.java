package com.fixai.platform.broker.application.service;

public class BrokerNotFoundException extends RuntimeException {

    public BrokerNotFoundException(String message) {
        super(message);
    }
}
