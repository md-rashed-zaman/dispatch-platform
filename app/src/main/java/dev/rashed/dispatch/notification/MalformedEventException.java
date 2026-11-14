package dev.rashed.dispatch.notification;

/**
 * An event that can never be processed, however often it's retried: not JSON, missing fields, or a version
 * this consumer doesn't know. The error handler sends it straight to the dead-letter topic, no retries.
 */
class MalformedEventException extends RuntimeException {

    MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }

    MalformedEventException(String message) {
        super(message);
    }
}
