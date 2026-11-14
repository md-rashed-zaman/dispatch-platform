package dev.rashed.dispatch.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rashed.dispatch.shared.DependencyUnavailableException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.CannotCreateTransactionException;

class ApiExceptionHandlerTest {

    final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void anUnreachableDatabaseOrStorageIsA503WorthRetrying() {
        var database = handler.unavailable(new CannotCreateTransactionException("Connection refused"));
        var storage = handler.unavailable(
                new DependencyUnavailableException("file storage", new RuntimeException("Connection refused")));

        assertThat(database.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(database.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(database.getBody().getType()).isEqualTo(URI.create("urn:dispatch:problem:unavailable"));
        assertThat(database.getBody().getDetail()).contains("the database").doesNotContain("Connection refused");
        assertThat(storage.getBody().getDetail()).contains("file storage");
    }

    @Test
    void anythingElseIsAProblemDetailsWithoutInternals() {
        var problem = handler.unexpected(new IllegalStateException("password=hunter2 at row 7"));

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getType()).isEqualTo(URI.create("urn:dispatch:problem:internal-error"));
        assertThat(problem.getDetail()).doesNotContain("hunter2");
    }
}
