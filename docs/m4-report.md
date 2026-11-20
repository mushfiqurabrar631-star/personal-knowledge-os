# M4 — Notion integration: report

Date: 2026-11-20. Status: **built and tested; live write test on the owner's phone pending.**

## Implemented
- **OkHttp transport** (`OkHttpNotionTransport`): JSON calls incl. PATCH, multipart upload, Retry-After pass-through,
  network errors as `IOException`; no automatic connection retries, nothing logged.
- **Token setup** (Settings → Notion connection): token typed only in the app, AES-GCM encrypted with an Android
  Keystore key, never shown again, removable. "Save and check" runs a **live, read-only** schema check; Auto sync can
  only be switched on after it succeeds. Removing the token turns sync off.
- **Room v4**: `notion_mappings`, write-ahead `notion_write_intents` (ADR 0002), `notion_conflicts`,
  `notion_kept_properties`, `sync_jobs` rows per item, attachment upload columns. AutoMigration 3→4 tested.
- **WorkManager sync**: unique work per item, network-only, engine-decided retry delays (Retry-After honoured).
  Triggered when processing finishes, on owner edits, on app start and by "Sync now". "Synced" is stored only after
  Notion read-back verification.
- **Safety**: idempotent by Capture ID; never a second page for the same Capture ID (lost create responses wait out
  index lag); partial PATCH (only changed properties); local-only items never sent; legacy 15 entries untouched
  (asserted in tests); no delete/archive/schema call exists.
- **Conflicts**: a Notion-side edit → item shows "Differs from Notion" with each differing field (Notion vs PKOS);
  nothing is written until the owner picks **Keep Notion's** (those fields become Notion-owned for the item) or
  **Send PKOS's** (only the differences are written). Lost local mapping + different page → adoption review.
- **Files**: Notion File Upload API (create → send), ≤ 5 MB per file (free plan). Uploaded before the page write;
  attached once and never re-sent; larger files stay on the phone and are marked.
- Item detail shows Notion status (verified time + "Open in Notion", waiting, retry reason, action needed).

## Tests (all pass)
| Suite | Tests |
|---|---|
| :core:notion — +6 M4 (partial PATCH, Keep Notion, Capture ID protected, file attach/no resend, upload limit/failure, overwrite diff-only) | 56/56 |
| :core:data — NotionSyncService 12 (create+verify, no-op resync, local-only, files + size limit, upload retry before create, 429/network retry, lost create no duplicate, bad token, live check, conflict keep/overwrite, adoption review) + migration 1→4 | 54/54 |
| :app — OkHttp transport 5 (incl. full engine over real HTTP via MockWebServer), Notion UI flows 4, earlier suites | 38/38 |
| Screenshots — M1 16, M2 8, M3 5, M4 4 tests (5 images in docs/screenshots/m4) | 33/33 |
| Lint | passes (4 advisory warnings: AGP 9.5.2 available, 2 plural suggestions) |
| assembleDebug / assembleRelease | ✅ (debug 76 MB, release unsigned 69 MB) |

Live schema was re-read on 2026-11-20 through the Notion connector (read-only): all 12 mapped properties present with
the expected types and the same options as approved.

## Live verification on your phone
api.notion.com is blocked from the build sandbox, and the token must never leave your phone, so the live write test
is yours:
1. Install `PKOS-M4-debug.apk`.
2. Notion: create an Internal integration (Read, Update, Insert content) and add it to *Master Database* → Connections.
3. PKOS → Settings → Notion connection → type the token → *Save and check* → expect "Connection works · 12/12".
4. Switch on *Auto sync*. Share a YouTube link to PKOS. Within a minute the item should show
   "In Notion, verified" and *Open in Notion* should open a new row with Capture ID filled.
5. Optional conflict check: change that row's Status in Notion, pin the item in PKOS → the item shows
   "Differs from Notion"; choose either option and confirm Notion matches your choice.
6. The 15 old rows must be unchanged.

## Limits
- Live API behaviour (real rate limits, upload acceptance) is verified only against the simulator until step 4 above.
- Files > 5 MB are not sent to Notion (free plan). Video/audio files are sent only if ≤ 5 MB.
- After "Keep Notion" for a field, PKOS stops updating that field for that item.
- Values PKOS has no option for (e.g. Low priority, other platforms) are still skipped, not created (ADR 0001).
