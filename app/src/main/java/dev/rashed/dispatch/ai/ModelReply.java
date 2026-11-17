package dev.rashed.dispatch.ai;

/**
 * What one model call returned: the raw text (not yet parsed or validated) and the tokens it cost. Also the
 * shape of a saved answer in the replay cache.
 */
record ModelReply(String model, String text, Integer inputTokens, Integer outputTokens) {}
