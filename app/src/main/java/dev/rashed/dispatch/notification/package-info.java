/**
 * Telling people what happened. Reacts to events from other modules and never makes business decisions.
 * Technicians hear in-process (Spring Modulith events); companies hear from Kafka, through an idempotent
 * consumer with retries and a dead-letter topic, as a separate notification service would. Log-only for now;
 * email and push come later behind {@link Notifier}.
 */
package dev.rashed.dispatch.notification;
