/**
 * Events leaving Dispatch, reliably (T17, ADR 0006). A module appends an event inside its own business
 * transaction; the relay sends committed events to Kafka afterwards. Either both the change and the event
 * are saved or neither is, and a saved event is sent at least once, even across crashes.
 */
package dev.rashed.dispatch.outbox;
