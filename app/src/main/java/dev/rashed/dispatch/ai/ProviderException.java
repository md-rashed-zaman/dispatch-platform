package dev.rashed.dispatch.ai;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import java.io.IOException;
import java.io.InterruptedIOException;

/**
 * A model call that failed at the provider or on the way there. {@link #retryable()} says whether trying again
 * (same model after a pause, or the next model) can help.
 */
class ProviderException extends RuntimeException {

    private final boolean retryable;
    private final boolean timedOut;
    private final Integer status;

    private ProviderException(String message, Integer status, boolean retryable, boolean timedOut, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.retryable = retryable;
        this.timedOut = timedOut;
    }

    /** No answer within the call timeout: the model is likely overloaded, so try another one rather than wait again. */
    boolean timedOut() {
        return timedOut;
    }

    boolean retryable() {
        return retryable;
    }

    /**
     * 503 "high demand", 500, 429 quota, timeouts and connection errors: retryable. Other 4xx (bad key, bad
     * request): our problem, and the same for every model, so retrying only burns time.
     */
    static ProviderException from(Throwable error) {
        for (var cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ApiException api) {
                boolean retryable = api.code() == 429 || api.code() >= 500;
                return new ProviderException(
                        api.code() + (api.status() == null || api.status().isBlank() ? "" : " " + api.status()) + ": "
                                + api.message(),
                        api.code(),
                        retryable,
                        false,
                        error);
            }
            if (cause instanceof GenAiIOException
                    || cause instanceof InterruptedIOException
                    || cause instanceof IOException) {
                boolean timedOut = hasCause(error, InterruptedIOException.class); // SocketTimeoutException is one
                return new ProviderException(
                        (timedOut ? "timed out: " : "no answer: ") + cause.getMessage(), null, true, timedOut, error);
            }
        }
        return new ProviderException(String.valueOf(error), null, false, false, error);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (var cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    /** For the audit row and logs: short, never the request (which may hold the key in a header). */
    String summary() {
        var message = getMessage();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    Integer status() {
        return status;
    }
}
