package com.fixai.platform.application.service;

public class BrokerAlreadyExistsException extends RuntimeException {

    public BrokerAlreadyExistsException(String message) {
        super(message);
    }
}
