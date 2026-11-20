# ADR 0002 — Body marker, write-ahead journal, adoption review

Status: Accepted (2026-11-20, after owner code review)

## Context
Review of v1 found three risks:
1. `bodyWritten` was inferred from "the page has any block", so an owner note could suppress the PKOS body.
2. When the local mapping was lost, a page found by Capture ID was updated immediately, which could overwrite edits made in Notion.
3. Crash safety after create / read-back / local save was not proven step by step.

## Decision
- **Marker:** the last PKOS body block contains `pkos-body:v1:<capture-id>`. Detection pages through block children and
  matches only that token. Too long to scan → `UNKNOWN`, no append.
- **Write-ahead journal (`SyncJournal`):** before each create / update / append, store `WriteIntent(kind, propertiesHash, pageId?)`.
  If storing fails, nothing is sent. The Room implementation lives next to `sync_jobs` (M2).
- **Adoption review:** without a local mapping, a found page is adopted silently only if its managed values equal what PKOS would
  write now or its journalled intent. Otherwise `Conflict(ADOPTION_REVIEW)`; the owner chooses Overwrite or Keep.
- **Index lag:** after a lost create response, an empty query is not trusted for `createLookupGraceMs` (120 s).

## Consequences
- After reinstall *without* restoring the journal, any local change made before the first sync is shown for review instead of
  being pushed. This is intentionally conservative.
- "Keep Notion" requires the app to pull the remote values into the local item; otherwise the next sync would push local values.
