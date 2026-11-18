/**
 * Human in the loop for finished work (step 3.5, T31). When a technician submits, an AI check compares the photos
 * with the checklist; {@link dev.rashed.dispatch.review.AuditPolicy} approves only when every item is shown and the
 * AI is sure enough, and sends everything else to a person. Every audit keeps what the AI said and what was
 * finally decided, so disagreements become eval data.
 */
package dev.rashed.dispatch.review;
