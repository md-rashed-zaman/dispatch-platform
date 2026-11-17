/**
 * AI assistance. Every model call goes through {@link dev.rashed.dispatch.ai.AiGateway}: versioned prompts,
 * timeouts, retries, model fallback, rate limits, a daily budget per company, metrics and an audit row per
 * attempt (ADR 0012, T28). Features expose use-case methods like {@link dev.rashed.dispatch.ai.SkillSuggestions},
 * never a generic "chat", and each has a non-AI fallback: AI helps, but nothing depends on it.
 */
package dev.rashed.dispatch.ai;
