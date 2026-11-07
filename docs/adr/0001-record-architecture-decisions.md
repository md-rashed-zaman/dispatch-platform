# 1. Record architecture decisions

Date: 2026-11-08
Status: Accepted

## Context

Dispatch makes many design choices (modular monolith over microservices, shared-schema tenancy, conditional updates for job acceptance, polling outbox before CDC). Months later, nobody remembers why a choice was made or which alternatives were rejected. That makes it hard to change the system safely, and hard to explain it in a review or interview.

## Decision

Every significant architecture decision is recorded as a short Architecture Decision Record (ADR) in `docs/adr/`, numbered in order, using this structure: Context, Decision, Alternatives considered, Consequences.

A decision is "significant" if it affects structure, data, reliability or security, or if someone could reasonably ask "why not X?".

ADRs are never edited to change a decision. A new ADR supersedes the old one, and the old one's status becomes "Superseded by N".

## Alternatives considered

- **No written decisions:** knowledge lives in people's heads and is lost.
- **A wiki:** separated from the code, drifts out of date, not reviewed with code changes.
- **Long design documents:** valuable for big features, but too heavy for every decision.

## Consequences

Good: decisions are reviewable in pull requests, searchable, and versioned with the code they explain.

Bad / accepted costs: a few minutes of writing per decision.
