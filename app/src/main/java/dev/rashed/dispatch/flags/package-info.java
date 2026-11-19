/**
 * Feature flags (step 4.4, ADR 0018, T35): per-company rollouts behind the OpenFeature API. A flag is on for a
 * company when it's enabled and the company is on its allow list, or not on its deny list and inside its rollout
 * percentage. The percentage is a stable hash of the flag and the company, so a company stays in its group as the
 * rollout grows. Platform admins change flags; every change is recorded with its reason.
 */
package dev.rashed.dispatch.flags;
