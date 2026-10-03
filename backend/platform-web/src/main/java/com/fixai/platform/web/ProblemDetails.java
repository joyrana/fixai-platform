package com.fixai.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** RFC 7807 helper with the platform's standard properties. */
public final class ProblemDetails {

    private ProblemDetails() {
    }

    public static ProblemDetail of(HttpStatus status, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://fixai.platform/problems/" + status.value()));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("path", request.getRequestURI());
        problem.setProperty("correlationId", CorrelationIdFilter.current(request));
        return problem;
    }
}
