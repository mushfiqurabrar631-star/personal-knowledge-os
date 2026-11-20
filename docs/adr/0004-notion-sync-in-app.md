# ADR 0004 — Notion sync inside the app (M4)

Status: Accepted (2026-11-20)

## Decisions
- **Transport:** OkHttp 5 (`OkHttpNotionTransport`), no automatic connection retries — the engine decides retries
  so a write is never repeated blindly. Bodies and headers are never logged.
- **Token:** typed only in Settings → Notion connection, AES-GCM encrypted with a non-exportable Android Keystore key,
  excluded from Android backups. Auto sync can only be switched on after a successful live, read-only connection check.
- **Local state (Room v4):** `notion_mappings` (verified link + remote hash), `notion_write_intents` (write-ahead
  journal, ADR 0002), `notion_conflicts` (owner decisions), `notion_kept_properties`, `sync_jobs` (attempts/errors),
  attachment upload columns.
- **Scheduling:** WorkManager, unique work `notion-<item id>` (APPEND_OR_REPLACE), network constraint, retry delays
  from the engine (Retry-After respected). Triggers: processing finished, owner edits, app start, "Sync now".
- **Partial PATCH:** an update sends only properties whose normalised value differs from the page. Unchanged values,
  including already-attached files, are never re-sent.
- **Keep Notion:** the differing properties become owner-owned for that item: PKOS never writes or compares them again.
  "Send PKOS's" writes only the differences.
- **Files:** Notion File Upload API, single part, ≤ 5 MB (free-plan limit). Upload happens before page create/update;
  a transient upload failure retries the whole sync, so no page is created without its files. Larger files stay on the
  phone and are marked `TOO_LARGE`. Unattached upload ids older than 50 min are re-uploaded.
- **FakeNotion** moved to `:core:notion` test fixtures so `:core:data` and `:app` tests run against the same simulator.

## Consequences
- After "Keep Notion" for Status, later Status changes in PKOS are not sent for that item (by design).
- A create whose response was lost or failed is treated as "maybe applied": PKOS waits ~2 min for Notion's index
  before creating, which can delay a retry but never duplicates a page.
