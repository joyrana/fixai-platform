package com.fixai.platform.broker.adapter.in.web;

import com.fixai.platform.broker.adapter.out.persistence.JdbcBrokerRepository;
import com.fixai.platform.broker.adapter.out.workflow.WorkflowApprovalGateway;
import com.fixai.platform.broker.application.port.outbound.ApprovalGateway;
import com.fixai.platform.broker.application.service.BrokerAlreadyExistsException;
import com.fixai.platform.broker.application.service.SessionConfigExceptions;
import com.fixai.platform.broker.application.service.BrokerNotFoundException;
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

    @ExceptionHandler(SessionConfigExceptions.NotFound.class)
    public ProblemDetail handleConfigNotFound(SessionConfigExceptions.NotFound exception, HttpServletRequest request) {
        return problemDetail(HttpStatus.NOT_FOUND, exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(SessionConfigExceptions.ValidationFailed.class)
    public ProblemDetail handleInvalidConfig(SessionConfigExceptions.ValidationFailed exception, HttpServletRequest request) {
        ProblemDetail problem = problemDetail(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage(), request.getRequestURI());
        problem.setProperty("violations", exception.violations());
        return problem;
    }

    @ExceptionHandler(SessionConfigExceptions.Conflict.class)
    public ProblemDetail handleConfigConflict(SessionConfigExceptions.Conflict exception, HttpServletRequest request) {
        ProblemDetail problem = problemDetail(HttpStatus.CONFLICT, exception.getMessage(), request.getRequestURI());
        problem.setProperty("codes", exception.codes());
        return problem;
    }

    @ExceptionHandler(JdbcBrokerRepository.BrokerInUseException.class)
    public ProblemDetail handleBrokerInUse(RuntimeException exception, HttpServletRequest request) {
        return problemDetail(HttpStatus.CONFLICT, exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(WorkflowApprovalGateway.ApprovalRefusedException.class)
    public ProblemDetail handleApprovalRefused(WorkflowApprovalGateway.ApprovalRefusedException exception, HttpServletRequest request) {
        ProblemDetail problem = problemDetail(HttpStatus.CONFLICT, exception.getMessage(), request.getRequestURI());
        problem.setProperty("codes", exception.codes());
        return problem;
    }

    @ExceptionHandler(ApprovalGateway.Unavailable.class)
    public ProblemDetail handleWorkflowUnavailable(RuntimeException exception, HttpServletRequest request) {
        return problemDetail(HttpStatus.SERVICE_UNAVAILABLE, "Approval service unavailable; retry later", request.getRequestURI());
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
        problemDetail.setProperty("correlationId", com.fixai.platform.web.CorrelationIdFilter.currentOrNew());
        return problemDetail;
    }
}
