package com.fixai.platform.certification.adapter.in.web;

import com.fixai.platform.certification.application.service.CertificationExceptions;
import com.fixai.platform.web.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Certification-specific RFC 7807 mappings. Validation, authorisation and unexpected errors are handled by the shared
 * {@code CommonExceptionHandler}, which never exposes internal details.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    @ExceptionHandler(CertificationExceptions.NotFound.class)
    ProblemDetail notFound(RuntimeException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(CertificationExceptions.InvalidRequest.class)
    ProblemDetail invalid(CertificationExceptions.InvalidRequest exception, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "The request is invalid", request);
        problem.setProperty("problems", exception.problems());
        return problem;
    }

    @ExceptionHandler({CertificationExceptions.IdempotencyConflict.class, CertificationExceptions.InvalidState.class})
    ProblemDetail conflict(RuntimeException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(CertificationExceptions.ApprovalRefused.class)
    ProblemDetail approvalRefused(CertificationExceptions.ApprovalRefused exception, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, exception.getMessage(), request);
        problem.setProperty("codes", exception.codes());
        return problem;
    }

    @ExceptionHandler(CertificationExceptions.TargetNotAllowed.class)
    ProblemDetail forbidden(RuntimeException exception, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, exception.getMessage(), request);
    }

    private static ProblemDetail problem(HttpStatus status, String detail, HttpServletRequest request) {
        return ProblemDetails.of(status, detail, request);
    }
}
