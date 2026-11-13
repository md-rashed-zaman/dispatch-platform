package dev.rashed.dispatch.web;

import dev.rashed.dispatch.shared.BusinessRuleException;
import dev.rashed.dispatch.shared.NotFoundException;
import dev.rashed.dispatch.shared.StaleVersionException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into Problem Details ({@code application/problem+json}), so clients handle every error
 * the same way. The base class already covers Spring MVC's own errors (malformed JSON, wrong types, ...).
 * Anything unexpected is left to Spring Boot: a generic 500 with no stack trace, logged on the server.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler
    ProblemDetail notFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Not found", ex.getMessage());
    }

    @ExceptionHandler
    ProblemDetail businessRule(BusinessRuleException ex) {
        var problem = problem(HttpStatus.CONFLICT, ex.type(), "Not allowed in the current state", ex.getMessage());
        ex.details().forEach(problem::setProperty);
        return problem;
    }

    @ExceptionHandler
    ProblemDetail staleVersion(StaleVersionException ex) {
        return problem(HttpStatus.PRECONDITION_FAILED, "stale-version", "Changed since you read it", ex.getMessage());
    }

    /** Two requests changed the same row at the same moment; @Version let only the first one win. */
    @ExceptionHandler
    ProblemDetail concurrentUpdate(ObjectOptimisticLockingFailureException ex) {
        return problem(
                HttpStatus.CONFLICT,
                "concurrent-update",
                "Changed by someone else at the same time",
                "Someone else changed this at the same time. Reload and try again.");
    }

    /**
     * A signed-in caller without the right role, denied by {@code @PreAuthorize} in a service. (Requests
     * without a valid token never get here: Spring Security answers those with 401 before any controller.)
     */
    @ExceptionHandler
    ProblemDetail accessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "You don't have permission to do this.");
    }

    /** 400 with every invalid field, so a form can show all problems at once. */
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = problem(HttpStatus.BAD_REQUEST, "invalid-input", "Invalid input", "Some fields are invalid.");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** 400 for invalid query parameters or headers, like {@code ?limit=500}, naming each one. */
    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = problem(HttpStatus.BAD_REQUEST, "invalid-input", "Invalid input", "Some parameters are invalid.");
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> Map.of(
                                "field",
                                String.valueOf(result.getMethodParameter().getParameterName()),
                                "message",
                                String.valueOf(error.getDefaultMessage()))))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:dispatch:problem:" + type));
        problem.setTitle(title);
        return problem;
    }
}
