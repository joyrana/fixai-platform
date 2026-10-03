package com.fixai.platform.web;

import org.springframework.http.HttpHeaders;

/** LOCAL DEVELOPMENT ONLY: identifies this service to peers running with security disabled. */
public class DevServiceIdentity implements OutboundAuthorization {

    private final String serviceName;

    public DevServiceIdentity(String serviceName) {
        this.serviceName = serviceName;
    }

    @Override
    public void apply(HttpHeaders headers) {
        headers.set(DevIdentityFilter.USER_HEADER, serviceName);
        headers.set(DevIdentityFilter.ROLES_HEADER, PlatformRoles.SERVICE);
    }
}
