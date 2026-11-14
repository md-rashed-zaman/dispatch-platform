/**
 * Shared kernel: small value objects every module speaks (money, location, skills, time windows, cursors for
 * pagination), and the few exception types that mean the same thing everywhere (not found, business rule,
 * stale version, invalid cursor). Only immutable types with no dependencies on other modules belong here.
 */
package dev.rashed.dispatch.shared;
