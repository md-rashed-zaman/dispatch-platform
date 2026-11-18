# AI limits and measured behaviour

Dispatch uses the Gemini API on the **free tier** (step 3.1). This page records what the free key really allows and how the models really behave, measured from Dhaka on 2026-11-17. Step 3.2's AI gateway is designed around these numbers.

## Key and models

- The key comes from Google AI Studio and lives only in `.env` (gitignored) as `GEMINI_API_KEY`. The app reads it from the environment (`spring.ai.google.genai.api-key: ${GEMINI_API_KEY:not-set}`); it is never in code, config files or logs. Without it the app still starts (the Gemini client refuses an empty key, hence the placeholder); only a model call fails, with 400 API_KEY_INVALID.
- Models, by alias (Google retires dated names, so we use `-latest` aliases and check `GET /v1beta/models`):

| Alias | Served as (2026-11-17) | Use |
|---|---|---|
| `gemini-flash-lite-latest` | `gemini-3.5-flash-lite` | Default: intake parsing, short answers |
| `gemini-flash-latest` | `gemini-3.8-flash` (per the console's usage) | Audit, summaries, images: rare calls only |
| `gemini-embedding-001` | 768 dimensions (`outputDimensionality`) | Similar work orders, search |

Pro models return 429 on the free tier.

## Rate limits

Free-tier limits are per Google Cloud project and per model, and Google changes them. Read from the AI Studio rate-limit page (https://aistudio.google.com/rate-limit) on 2026-11-17, "Default Gemini Project", free tier:

| Model (console name) | Alias we call | RPM | TPM | RPD |
|---|---|---|---|---|
| Gemini 3.5 Flash Lite | `gemini-flash-lite-latest` | 15 | 250K | 500 |
| Gemini 3.8 Flash | `gemini-flash-latest` | 5 | 250K | **20** |
| Gemini Embedding 1 | `gemini-embedding-001` | 100 | 30K | 1,000 |
| Gemini 2.5 Pro, 3.1 Pro | (not used) | 0 | 0 | 0 |

Flash gets **20 requests a day**. That decides its role: rare, high-value calls only (a work order audit, a weekly summary), never anything per request. Failed calls count too: after the 7 Flash calls below, none of which returned an answer, the console showed 5 of the 20 used.

RPM = requests per minute, TPM = input tokens per minute, RPD = requests per day (resets at midnight Pacific time). Going over returns **429 RESOURCE_EXHAUSTED**; the gateway keeps a client-side limiter below the RPM and backs off on 429.

## Measured

Same short prompt, plain `curl` to `generateContent`:

| What | Result |
|---|---|
| Flash-Lite, 10 calls | 7 × 200, **3 × 503** "high demand". Successful calls 1.25-6.5 s, median 2.7 s |
| Flash, 7 calls over 30 min | **0 succeeded**: 503 after 0.6-12 s, or no answer at all within 40 s |
| Tokens, one-sentence question with a system message | 34 prompt + 15 output = 49 |
| `countTokens` vs the real call | 13 vs 14 prompt tokens for the same text: count is an estimate, bill on `usageMetadata` |
| Structured output (`responseJsonSchema`) | Valid JSON matching the schema, 14 + 48 tokens |
| Spring AI `ChatClient`, first call in a fresh JVM | 9.4 s (client start-up plus the call) |
| Embeddings, cosine similarity | "Printer shows error E05" vs "office printer is not printing" **0.66**; vs "CCTV camera has no signal" **0.55** |
| Embeddings, a batch of 60 texts, then another within the minute (step 3.4) | First 200 in 2.1 s; second **429** `EmbedContentRequestsPerMinute…FreeTier` = 100: **every text in a batch counts as one request**. So the free tier embeds 100 texts a minute and, by the same count, 1,000 a day |
| Temperature 0, same prompt 3 times | 3 different skill codes (`printer-repair`, `canon-printer-e05-error`, `canon-error-e05`) |

## What this means for the design

1. **The daily cap is the real limit.** Flash-Lite's 500 a day is roughly one call per work order created for a small tenant, Flash's 20 a day is almost nothing. Quotas are per project, shared by every tenant, so the gateway needs per-tenant budgets on top (step 3.2).
2. **503 is normal, not an outage.** Even the lightest model failed 3 calls in 10. Every call needs retry with backoff and a fallback chain (Flash -> Flash-Lite -> the feature's non-AI path, step 3.2).
3. **A call can hang.** Flash sometimes sent nothing for 40 s. Every call needs a hard timeout; without one, a request thread is stuck and the failure spreads (T20).
4. **Temperature 0 does not mean the same answer.** Never let the model invent identifiers: give it the closed list (the tenant's skill codes) and validate the reply like any untrusted input (step 3.3).
5. **Embeddings separate meaning, but not by much.** 0.66 vs 0.55 is a small gap: thresholds must be tuned on real examples, not guessed.
6. **Latency is seconds, not milliseconds.** AI calls never sit on a hot path the user waits for without feedback; they run async or behind a clear "thinking" state.

For embeddings that means: re-embedding 50,000 technician profiles takes about 55 days on the free tier (900 a day, leaving room for work orders) and minutes on a paid tier. Matching handles it by design: a paced backfill, and ranking without the semantic part for technicians not embedded yet (ADR 0013).

Photo checks (step 3.5): a vision call with one 640 px photo is about 1,060 image tokens plus 40-400 text tokens, and took about 9 s on Flash-Lite; in the 100-job eval, 2 timed out at 15 s. The call timeout went from 10 s to 15 s, and the deadline to 35 s. The audit runs off the request path, so nobody waits for it.

## How to re-measure

```bash
export $(grep ^GEMINI_API_KEY= .env)   # only the key: .env also holds example passwords
curl -s -H "x-goog-api-key: $GEMINI_API_KEY" -H 'Content-Type: application/json' \
  "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-lite-latest:generateContent" \
  -d '{"contents":[{"role":"user","parts":[{"text":"Say hello"}]}]}' | jq '.usageMetadata'
```

Through Spring AI: `cd app && ./mvnw test -Dtest=GeminiChatClientTests` with the key exported (the test is skipped without it, so CI never calls the API).

Only synthetic data goes to the free tier: Google may use free-tier prompts to improve its models.
