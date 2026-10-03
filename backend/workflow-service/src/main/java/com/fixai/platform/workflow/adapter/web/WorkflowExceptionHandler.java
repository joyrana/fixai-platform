package com.fixai.platform.workflow.adapter.web;

import com.fixai.platform.web.ProblemDetails;
import com.fixai.platform.workflow.application.service.WorkflowExceptions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class WorkflowExceptionHandler {

    @ExceptionHandler(WorkflowExceptions.NotFound.class)
    ProblemDetail notFound(RuntimeException exception, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(WorkflowExceptions.PolicyViolation.class)
    ProblemDetail policy(WorkflowExceptions.PolicyViolation exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetails.of(HttpStatus.CONFLICT, exception.getMessage(), request);
        problem.setProperty("codes", exception.codes());
        return problem;
    }

    @ExceptionHandler(WorkflowExceptions.Conflict.class)
    ProblemDetail conflict(RuntimeException exception, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.CONFLICT, exception.getMessage(), request);
    }
}
