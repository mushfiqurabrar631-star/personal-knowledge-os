# ADR 0001 — Notion target and sync design

Status: Accepted (2026-11-20)

## Context
The spec targets "My knowledge Hub → Master Database". Inspection showed `Notes` is a select,
`category ` / `platform ` have trailing spaces, options are limited, and there was no field for
an idempotency key. A separate "Knowledge Vault" database was also created earlier the same day.

## Decision
- Use the existing Master Database (owner's choice). Knowledge Vault is not used by PKOS.
- Add exactly one property, `Capture ID` (rich text), for duplicate prevention.
- Put AI/extra information in page content blocks instead of new properties.
- Write only pre-existing select options; report the rest as proposals.
- Auth: a Notion internal integration token entered once in the app and stored with
  Android Keystore. Public OAuth was rejected because it needs a client secret on a server.
- Sync core is a pure Kotlin/JVM module (`:core:notion`) behind an `HttpTransport` interface,
  so it is testable without Android. The app supplies an OkHttp transport.
- Conflict detection uses a hash of managed properties, not `last_edited_time`
  (which Notion rounds to the minute).

## Consequences
- Low/Critical priority, Archived status and non-YouTube/Facebook platforms are kept locally only.
- If the owner later adds options in Notion, PKOS starts using them automatically (live schema).
