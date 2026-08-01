package com.fixai.platform.application.service;

public class BrokerNotFoundException extends RuntimeException {

    public BrokerNotFoundException(String message) {
        super(message);
    }
}
