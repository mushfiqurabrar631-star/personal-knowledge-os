# M6 — Search & Advanced Features (spec §9)

Date: 2026-11-22 · DB schema v5 → **v6** (additive AutoMigration, tested) · no new paid service, no new permission.

## What was built (all real, wired to the database)

| # | Feature | Implementation | Honest limits |
|---|---|---|---|
| 1 | Semantic search | `SearchService`: FTS4 keyword index (title, text, summary, note, OCR/PDF text, tags, category/topic, URL) — always on, offline. “Meaning” layer: bilingual related-word groups (bn↔en) + trigram spelling match (offline) + optional Gemini embeddings (`gemini-embedding-001`, 256 dims) only with cloud AI **and** semantic search on; local-only items are never embedded. Filters: category, status, type, priority, date, pinned, in-Notion. Each result shows *why* it matched. | Offline “meaning” is word-group + spelling based, not true vector semantics. Cloud embeddings need the owner’s Gemini key; not verified against the live API from the sandbox. |
| 2 | Knowledge chat | `KnowledgeChat`: retrieves top 6 own items, answers only from them with `[n]` citations; citations are validated (invalid/missing → answer withheld), `NOT_IN_LIBRARY` → “not found”. Local-only items never enter the cloud prompt (count shown). Offline: extractive answer (best sentence per source, cited). Sources open the item. | Single-turn Q&A (no follow-up memory). Cloud answer path tested with a fake generator only. |
| 3 | Knowledge graph | `GraphService`: per-item edges (category, topic, subtopic, tags, shared tags, same topic, similar text, duplicate, owner links) with reason + confidence + AI/USER origin. Owner can link/unlink items (stored in `knowledge_links`). Connections screen: radial map + explained list. | Per-item view only; no whole-library graph canvas. |
| 4 | Auto topic creation | Processing reuses an existing topic by exact or near-identical name (normalized + trigram ≥0.82); max 30 automatic topics per category; Topics screen suggests merges of near-duplicates, merged only on the owner’s tap, “keep separate” remembered. | — |
| 5 | Smart recommendations | Related items on the item screen and “Next useful” on Home (to-learn / in-progress / pinned, active topics first), each dismissible and remembered. | Rule-based, local. |
| 6 | Knowledge timeline | Day / week / month buckets: saved, owner edits, AI organisation, most-active topics, tappable items. | — |
| 7 | Action extraction | bn/en patterns (checkboxes, TODO:, need to/must, “করতে হবে” …, list verbs, ideas) + `action_items` from cloud AI. Always PROPOSED; becomes a task only on **Confirm**; **no due date is ever inferred** — only the owner can pick one. Tasks screen + item screen. | Tasks live in PKOS only (not pushed to Notion/calendar). |
| 8 | Forgotten-knowledge reminder | **Off by default.** When switched on: daily WorkManager check, one quiet notification for a stale important item, re-reminds the same item only after the owner’s interval (3/7/14/30 days); “Rediscover” on Home. Needs notification permission (requested only when switched on). | Notification delivery must be checked on a real phone. |
| 9 | Smart collections | Manual (add/remove from item screen) and rule-based (category, topic, status, priority, type, platform, tag, pinned, saved-within-days; all/any; “not”), evaluated live. Deleting a collection never deletes items. | — |
| 10 | Export & recovery | One .zip via the system file picker: `pkos-export.json` (all fields, topic path, tags, actions, Notion URL, field origins), `items.csv` (UTF-8 BOM, formula-injection safe), `markdown/` (one file per item), `attachments/`, `integrity-report.json` (SHA-256 of every file, `secrets_included:false`). Local-only items excluded unless the owner opts in. Tokens/keys are not in the DB and are never exported. Integrity check: SQLite `integrity_check`, `foreign_key_check`, every attachment present + SHA-256. Failed export removes the partial file. Encrypted Drive backup/restore (M5) unchanged. | Import of an export zip is not implemented (recovery = M5 encrypted backup/restore). |

## Database (migration safety)
v6 adds only new tables: `knowledge_fts` (FTS4), `embeddings`, `knowledge_links`, `action_items`, `dismissals`. No existing column or row is changed. `MigrationTest.v1ToV6KeepsExistingItems` migrates v1→…→v6 with schema validation and reopens with Room (no destructive fallback). The search index rebuilds itself from existing items.

Found and fixed during regression: SQLite’s `integrity_check` needs write access to validate an FTS4 index — the M5 backup snapshot check (read-only open) would have failed every backup on v6. Snapshot/staging copies are now opened read-write (never the live DB); all 13 backup/restore tests pass again. Also fixed a lint-caught API-35-only call in the timeline.

## Privacy / cost
Cloud calls (answers, embeddings) only with *Cloud AI* on and the owner’s own Gemini key; failures fall back to offline results silently. Notion integration, Drive backup, recovery, and owner-edited fields (field origins) are untouched.

## Known limitations (M6 as shipped)
- **Knowledge Chat is single-turn.** Each question is answered on its own; earlier questions in the conversation are not used as context, and the conversation is not saved (cleared when the screen closes).
- **The knowledge graph is per item.** The Connections screen shows one item and its neighbours; there is no whole-library graph view.
- **No import of the export ZIP.** The export is a portable copy (JSON/CSV/Markdown/files). Restoring the app itself uses the M5 encrypted Google Drive backup/restore; an export ZIP cannot be read back into PKOS.
- Offline “meaning” search = related-word groups + spelling similarity; vector similarity needs cloud AI with the owner’s Gemini key.
- Confirmed tasks stay inside PKOS (not sent to Notion or a calendar).

## 1. Automated tests (run in the build sandbox, Robolectric/JVM — no emulator)
Final full regression, 2026-11-22 02:58 (+06), clean build, `versionName 0.6.0-m6`:

| Suite | Result | Includes |
|---|---|---|
| `:core:notion:test` | 56/56 ✅ | Notion schema guard, mapper, sync engine |
| `:core:data:testDebugUnitTest` | 93/93 ✅ | `M6FeaturesTest` (12), `MigrationTest.v1ToV6KeepsExistingItems` (Room v1→v6 with schema validation), backup/restore 13, Notion sync, processing |
| `:app:testDebugUnitTest` (flow tests) | 53/53 ✅ | `M6FlowTest` (7), AppFlowTest, BackupFlowTest, NotionFlowTest, ItemEditFlowTest … |
| `:app:recordRoborazziDebug` (screenshot tests) | 43/43 ✅ | `ScreenshotM6Test` (7 tests → 10 images in `docs/screenshots/m6`) |

Cloud paths (Gemini answers, embeddings) are tested only with fake HTTP replies and fake generators — never against the live API.
Flaky fix during verification: `ScreenshotM6Test.m07_connections` failed once in a full run (timeout) because the item screen's related list loads asynchronously and shifted the “See connections” button; the test now waits for that list. It then passed 3/3 standalone runs and in the final full run.

## 2. Local builds and static checks (build sandbox)
- Lint: `:app:lintDebug`, `:core:data:lintDebug` — 0 errors ✅ (23 warnings in app, pre-existing kinds).
- `:app:assembleDebug` (signed with the owner's local, untracked debug key) and `:app:assembleRelease` (unsigned) — both OK ✅ (`app-debug.apk` 0.6.0-m6-debug, `app-release-unsigned.apk` 0.6.0-m6).
- CI (GitHub Actions, `.github/workflows/android.yml`): tests → lint → screenshots → debug + release APKs; artifacts
  `pkos-<run>-debug-apk`, `pkos-<run>-release-unsigned-apk`, `pkos-<run>-screenshots`, `pkos-<run>-test-and-lint-reports`.
  CI signs debug with the standard Android debug key; no signing key or secret is stored in GitHub.

## 3. Real-device verification (NOT yet done — owner, on the phone)
Install the debug APK (`com.pkos.app.debug`, version 0.6.0-m6-debug). None of the steps below has been performed yet.

### A. Reminder notifications (opt-in)
1. Settings → Knowledge tools: confirm *Forgotten knowledge reminders* is **off** on a fresh install.
2. A reminder is only for an important item (pinned, high/critical priority, *To learn* or *To review*) not opened for 30 days. On a fresh library no item qualifies yet, so steps 4–5 can only be checked after 30 days of real use; steps 1, 3 and 6 can be checked now.
3. Switch reminders **on** → on Android 13+ the notification-permission prompt appears. Allow it.
4. The check runs once a day (WorkManager, first run about a day after switching on; there is no built-in “run now” button). Expected: one quiet “A save worth revisiting” notification; tapping it opens that item.
5. Deny the permission (or turn reminders off) → no notification appears; Home shows no “Rediscover” section while off.
6. Pass if: off by default, permission asked only when switched on, at most one notification per run, the same item does not come back before the chosen interval.

### B. Export via the system file picker (SAF)
1. Settings → Data → Export → keep *Include local-only items* **off**, *Include files* on → *Create export file*.
2. Choose Downloads (and once Google Drive in the picker) → save `pkos-export-<date>.zip`.
3. Expected message: “Export finished: N items, M files, K local-only items left out”.
4. Open the ZIP in a file manager: `pkos-export.json`, `items.csv`, `markdown/`, `attachments/`, `integrity-report.json`.
5. Open `items.csv` in Google Sheets/Excel (Bengali text must display correctly), a `.md` file in a notes app, and check that no local-only item and no token/key text appears anywhere.
6. Cancel the picker once → nothing is written; the library is unchanged.
7. *Check now* (integrity) → “All good …”.

### C. Gemini integration (owner's own free-tier key)
1. Settings → AI settings: save the key; Settings → *Cloud AI* on; Knowledge tools → *Search by meaning* on.
2. Save 2–3 new normal items and wait for organizing (embeddings are made after processing, 10 per run).
3. Search a synonym that is not in the text (e.g. “vehicle” for a “car” note) → result with the “Similar meaning” badge.
4. Ask a question answered by those items → card “AI answer · from your items” with [n] sources that open the item.
5. Mark one item *local-only*, ask about it → it must not appear in a cloud answer; the note “1 local-only item(s) were not sent” appears.
6. Ask something not in the library → “No answer found in your saved knowledge”.
7. Turn Cloud AI off or airplane mode → search and Ask still work (on-phone answer). No paid service is used at any point.

Still pending from earlier milestones: M2 floating capture, M4 live Notion write, M5 real Drive backup + clean-install restore.
