package com.fixai.platform.certification.adapter.in.web;

import com.fixai.platform.certification.application.service.CertificationExceptions;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** RFC 7807 responses. Never exposes stack traces or internal exception messages for unexpected errors. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

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

    @ExceptionHandler(CertificationExceptions.TargetNotAllowed.class)
    ProblemDetail forbidden(RuntimeException exception, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<String> problems = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage()).toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Request validation failed", request);
        problem.setProperty("problems", problems);
        return problem;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail unreadable(Exception exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed request", request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unhandled error for {} {}", request.getMethod(), request.getRequestURI(), exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", request);
    }

    private static ProblemDetail problem(HttpStatus status, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://fixai.platform/problems/" + status.value()));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("path", request.getRequestURI());
        problem.setProperty("correlationId", CorrelationIdFilter.current(request));
        return problem;
    }
}
