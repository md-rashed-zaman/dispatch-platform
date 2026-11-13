/**
 * Shared kernel: small value objects every module speaks (money, location, skills, time windows), and the
 * few exception types that mean the same thing everywhere (not found, business rule, stale version). Only
 * immutable types with no dependencies on other modules belong here.
 */
package dev.rashed.dispatch.shared;
