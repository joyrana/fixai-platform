package com.fixai.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Fallback handlers shared by all services (lowest precedence so service-specific advice wins). Unexpected errors
 * return a generic message; details go to the log only.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class CommonExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommonExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<String> problems = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage()).toList();
        ProblemDetail problem = ProblemDetails.of(HttpStatus.BAD_REQUEST, "Request validation failed", request);
        problem.setProperty("problems", problems);
        return problem;
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail parameterValidation(HandlerMethodValidationException exception, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.BAD_REQUEST, "Request parameter validation failed", request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail unreadable(Exception exception, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.BAD_REQUEST, "Malformed request", request);
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    ProblemDetail denied(Exception exception, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.FORBIDDEN, "Insufficient permissions for this operation", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail noResource(NoResourceFoundException exception, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, "Not found", request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unhandled error for {} {}", request.getMethod(), request.getRequestURI(), exception);
        return ProblemDetails.of(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", request);
    }
}
