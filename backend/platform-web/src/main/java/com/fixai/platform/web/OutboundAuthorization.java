package com.fixai.platform.web;

import org.springframework.http.HttpHeaders;

/** Adds this service's credentials to outbound service-to-service calls. User tokens are never forwarded. */
public interface OutboundAuthorization {

    void apply(HttpHeaders headers);
}
