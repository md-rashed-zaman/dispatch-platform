/**
 * The ops assistant (step 4.5, ADR 0019, T36): an MCP server that lets a dispatcher's AI client (Claude Desktop,
 * Claude Code, MCP Inspector) search and act on Dispatch with the dispatcher's own permissions. Every tool calls the
 * same services the web app does, under the caller's token, so role checks and row-level security apply unchanged.
 * The one write tool needs a confirmation from a preview first, and every call is recorded.
 */
package dev.rashed.dispatch.assistant;
