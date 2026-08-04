package com.fixai.platform.adapter.in.web;

import com.fixai.platform.application.service.BrokerAlreadyExistsException;
import com.fixai.platform.application.service.BrokerNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BrokerNotFoundException.class)
    public ProblemDetail handleNotFound(BrokerNotFoundException exception, HttpServletRequest request) {
        return problemDetail(HttpStatus.NOT_FOUND, exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(BrokerAlreadyExistsException.class)
    public ProblemDetail handleConflict(BrokerAlreadyExistsException exception, HttpServletRequest request) {
        return problemDetail(HttpStatus.CONFLICT, exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return problemDetail(HttpStatus.BAD_REQUEST, detail, request.getRequestURI());
    }

    private ProblemDetail problemDetail(HttpStatus status, String detail, String path) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setType(URI.create("https://fixai.platform/problems/" + status.value()));
        problemDetail.setTitle(status.getReasonPhrase());
        problemDetail.setProperty("path", path);
        return problemDetail;
    }
}
