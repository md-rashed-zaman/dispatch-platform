package dev.rashed.dispatch.web;

import dev.rashed.dispatch.shared.BusinessRuleException;
import dev.rashed.dispatch.shared.DependencyUnavailableException;
import dev.rashed.dispatch.shared.InvalidCursorException;
import dev.rashed.dispatch.shared.NotFoundException;
import dev.rashed.dispatch.shared.StaleVersionException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into Problem Details ({@code application/problem+json}), so clients handle every error
 * the same way. The base class already covers Spring MVC's own errors (malformed JSON, wrong types, ...).
 * Anything unexpected becomes a generic 500 Problem Details, with the stack trace only in the server log.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

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

    /**
     * The database or storage is down. 503 with Retry-After tells clients (and load balancers) that the request
     * was fine and that trying again shortly is the right move, instead of a bare 500 (docs/failure-modes.md).
     */
    @ExceptionHandler({
        DependencyUnavailableException.class,
        CannotCreateTransactionException.class,
        DataAccessResourceFailureException.class
    })
    ResponseEntity<ProblemDetail> unavailable(Exception ex) {
        var what = ex instanceof DependencyUnavailableException d ? d.dependency() : "the database";
        log.warn("Request failed because {} is unavailable: {}", what, ex.getMessage());
        var problem = problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "unavailable",
                "Temporarily unavailable",
                "Dispatch can't reach " + what + " right now. Try again in a few seconds.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem);
    }

    /**
     * Anything not handled more specifically: logged with its stack trace on the server, and answered in the
     * same Problem Details format as every other error, without internals.
     */
    @ExceptionHandler
    ProblemDetail unexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error",
                "Something went wrong",
                "Something went wrong on our side. It has been logged.");
    }

    @ExceptionHandler
    ProblemDetail invalidCursor(InvalidCursorException ex) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-cursor", "Invalid cursor", ex.getMessage());
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
