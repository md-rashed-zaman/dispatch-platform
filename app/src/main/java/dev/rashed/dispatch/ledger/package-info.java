/**
 * A small double-entry ledger (step 4.3, ADR 0017, T34). Every money movement is one {@link
 * dev.rashed.dispatch.ledger.Ledger.Entry} whose debits equal its credits, checked here and again by the database
 * at commit. Entries are never changed: a wrong one is reversed by a mirror entry. Money is integers in minor units.
 */
package dev.rashed.dispatch.ledger;
