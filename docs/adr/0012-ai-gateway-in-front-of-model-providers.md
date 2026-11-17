# 12. An AI gateway in front of model providers

Date: 2026-11-17
Status: Accepted

## Context

Phase 3 adds AI features: skill suggestions now, then intake parsing, photo checks and an ops assistant. Step 3.1 measured the free Gemini tier we build on ([docs/ai-limits.md](../ai-limits.md)):

- **Failures are routine.** Flash-Lite returned 503 on 3 calls in 10; Flash answered none of 7.
- **Calls can hang.** Some calls sent nothing for over 40 s.
- **Quotas are tight and shared by every company.** Flash-Lite allows 15 requests a minute and 500 a day; Flash allows 20 a day.
- **Answers are untrusted text.** At temperature 0, the same prompt gave three different answers in three calls.

Calling the provider from each feature would repeat timeouts, retries, quota handling, logging and redaction in every feature, and each copy would drift. Spring AI's defaults also don't fit these numbers:
- its retry template makes up to 10 attempts with growing pauses, inside one call;
- the Google client has its own timeouts and retries.

## Decision

Every model call goes through one `AiGateway` in the `ai` module. Features call use-case methods (`SkillSuggestions.suggest`), never a generic "chat", and each feature has a non-AI fallback.

| Concern | How |
|---|---|
| Timeouts | Our own Google client: 10 s per HTTP call, the client's own retries off, Spring AI's retry off. A deadline of 25 s per feature call, across all tries |
| Retries | Twice per model on 503, 429 or a connection error, with 1 s and 2 s pauses (±25% jitter). A timeout moves straight to the next model. Other 4xx (bad key) don't retry at all |
| Fallback chain | Per feature, in config: `skill-suggestions: [gemini-flash-lite-latest, gemini-3.1-flash-lite]`. The second model has its own quota and servers |
| Circuit breaker | One per model: when half of the last 10 calls failed, skip that model for 30 s |
| Rate limits | Client-side, per model, under the provider's per-minute quota (12 of 15). No permit means the next model, not a wait |
| Budget | Provider calls per company per day (100), counted in `ai_call`. Fails closed: if the count can't be read, don't spend |
| Prompts | `resources/prompts/<feature>/<version>.st`; the version is recorded on every call |
| Validation | The answer is parsed into a record (JSON schema in the prompt); a reply that doesn't parse tries the next model. Features then check values (for skills: only codes technicians have) |
| Privacy | Emails and phone numbers are redacted before the prompt is built, so they never leave, and before storing |
| Audit | One `ai_call` row per attempt: feature, prompt version, model, outcome, redacted input, output, tokens, latency. Written in its own short transaction, under row-level security |
| Metrics | Calls by feature, model and outcome (with latency histogram); tokens and list-price cost by company; fallbacks |
| Tests and evals | A record-and-replay cache keyed by feature, prompt version and a hash of the prompt. `replay` mode never calls the provider |

The gateway runs outside any database transaction: a model call takes seconds and must not hold a connection.

## Alternatives considered

| Option | Verdict |
|---|---|
| Call `ChatClient` from each feature | Rejected: every feature re-solves timeouts, retries, quota, audit and redaction, differently |
| Spring AI's retry and the Google client's retry | Rejected: retries hidden inside one call, with no deadline, no model fallback and nothing recorded. One layer, ours, owns retries |
| A separate gateway service (LiteLLM, a cloud AI gateway) | Not now: one app and one provider. The module boundary is where it would be split out if several services needed it |
| Fail open on the budget | Rejected: a database outage would remove the spending limit exactly when nobody is watching |
| Semantic caching (similar prompts share an answer) | Later: exact-match replay first; similar is not the same for "which skills" |

## Consequences

- An AI feature can be slow or unavailable, but it can't fail a request. With a bad key, or the provider blocked or hanging, the API answers 200 with `available: false` and the form works as before; this is checked end to end.
- Worst case for a user waiting: about 21 s (two hung models). The UI shows progress and never blocks the form.
- Rate limits are per instance (shortcut, noted in `AiConfig`); with several instances the counter moves to Redis.
- `ai_call` grows with use: one row per attempt. Retention (for example 90 days) comes with the audit features of a later step.
- Changing a prompt means a new version file, so old audit rows and recorded answers stay interpretable.
