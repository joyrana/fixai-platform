package com.fixai.platform.broker.application.service;

public class BrokerAlreadyExistsException extends RuntimeException {

    public BrokerAlreadyExistsException(String message) {
        super(message);
    }
}
