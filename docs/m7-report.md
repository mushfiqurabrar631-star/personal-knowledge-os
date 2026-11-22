# M7 — Hardening (security, accessibility, performance, regression)

Date: 2026-11-22 · Version 0.6.0-m6 → **0.7.0-m7** · DB schema stays **v6** (no migration in M7) · one new dependency (round 2): `zip4j` 2.11.5 for the password-protected export required by spec §10.
No feature outside the spec was added; round 2 added only the missing spec §10/§10.1 controls. Notion schema, backup/restore engine and recovery code were not changed (Drive *disconnect* now also revokes the grant).

Step-by-step phone checks (Bengali): [m7-acceptance-guide.md](m7-acceptance-guide.md).

Legend: ✅ verified by an automated test or a check run in this session · ⏳ **Pending** (needs the real phone, an account or credentials) · ⚠️ open gap

## 1. Security audit

| Area | Finding | Result |
|---|---|---|
| Secrets in Git | Full history scanned (Gemini `AIza…`, Notion `ntn_/secret_`, Google `ya29.`, private keys, keystores, `local.properties`). No API key, token or recovery key was ever committed. `.gitignore` covers `local.properties`, `*.keystore`, `*.jks`, `*.p12`, `keystore/`, `signing.properties`, `secrets.properties`. | ✅ |
| Old debug keystore | `keystore/debug.keystore` (password `android`) is still in **old commits** (removed + rotated in M5). The current debug key is local and untracked; the old key signs nothing. Removing it from history requires a force-push rewrite — owner decision, not done. | ⚠️ proposal in [security-debug-keystore-history.md](security-debug-keystore-history.md); no force-push |
| Logs | No `Log.*`, `println`, `printStackTrace` or HTTP logging interceptor in app/core main code. Tokens, prompts, text and recovery keys are never logged. | ✅ |
| Secrets at rest | Notion token, Gemini key and backup KEK: AES-256-GCM with a non-exportable Android Keystore key; only ciphertext in private prefs; excluded from Android backup (`allowBackup=false`, extraction rules exclude everything). | ✅ code review; Keystore behaviour ⏳ phone |
| Local DB / attachments | App-private storage; FileProvider exposes only `files/attachments/`, not exported, per-intent read grant. | ✅ |
| **Share target accepted `file://` URIs (fixed)** | `ShareActivity` is exported; a malicious app could share `file:///data/data/com.pkos.app/databases/pkos.db` (or the secrets file) and PKOS would read its own private file and save it as an attachment (which could then go to Notion/Drive). Now only `content://` URIs are accepted. Test: `ShareCaptureTest.refusesFileUrisPointingAtPrivateAppFiles` (DB file, secrets file, attachment path; parser + real ShareActivity → no attachment). Share tests now use a real `content://` provider. | ✅ fixed |
| Network | All endpoints are hard-coded `https://` (Notion, Drive, Gemini). Added `network_security_config.xml`: cleartext **off**, system CAs only (user-installed CAs not trusted). | ✅ config; ⏳ phone TLS check |
| OAuth | Drive uses only `drive.file` (files PKOS created). **Fixed (round 2):** Disconnect now also revokes PKOS's Google grant (`oauth2.googleapis.com/revoke`, no UI); if Google cannot be reached it says so and shows myaccount.google.com/permissions. Test: `M7PrivacyFlowTest` (revoked / not confirmed). | ✅ code; ⏳ real Google |
| Local-only → cloud (release blocker) | Verified at every path: processing (cloud AI), embeddings, Ask prompt, Notion sync. New `M7SecurityTest.localOnlyNeverLeavesThePhone` uses spy providers and also covers switching an item to local-only later (its stored cloud embedding is deleted, nothing is sent afterwards). | ✅ |
| **No per-item local-only switch (fixed)** | Spec §10 requires "local-only per item"; only a global default existed. Item ⋮ menu now has *Keep on this phone only / Allow cloud AI and Notion*; detail shows the privacy state. Pages already in Notion are left untouched. | ✅ fixed (UI ⏳ phone) |
| Notion/Drive failures vs local data | Existing tests: Notion 401/403/404/409/429/500, lost responses, conflicts; Drive auth loss, full Drive, interrupted upload, checksum mismatch, wrong key, failed restore rollback — none deletes or changes local items. | ✅ (re-run below) |
| Export privacy | **Fixed (round 2):** spec §10 "protect archive if it contains private material" — including local-only items now **requires a password** (≥ 8 chars, typed twice) and every file in the ZIP is AES-256 encrypted (standard WinZip-AES via `zip4j` 2.11.5, Apache-2.0, the only new dependency). Optional for normal exports. Enforced in the service too. Tests: `M7PrivacyTest.exportWithPrivateItemsNeedsAPasswordAndIsAesEncrypted`, `M7PrivacyFlowTest`. | ✅ |

## 1a. Spec §10.1 privacy controls (round 2)

| Control | Where | Evidence |
|---|---|---|
| Cloud AI on/off | Settings → AI | existing (M3) |
| Local-only capture default | Settings | existing (M2) + per item (M7) |
| Backup now / pause backup | Backup | existing (M5) |
| Notion sync on/off | Notion settings | existing (M4) |
| Disconnect Notion | Notion settings → *Disconnect Notion*: token removed, sync off, pages untouched; text explains removing the integration in Notion | `NotionFlowTest` |
| Disconnect Google Drive | Backup → *Disconnect*: backups stop, Google grant revoked | `M7PrivacyFlowTest` ×2 |
| Delete local cache vs delete all data, explained separately | Settings → **Privacy & data**: *Clear temporary cache* (temp files + rebuildable search index; data kept) vs *Delete all data on this phone* (items, files, settings, token, AI key, recovery key on phone; typed confirmation word; Notion pages and Drive backups explicitly **not** deleted; app returns to onboarding) | `M7PrivacyTest`, `M7PrivacyFlowTest` |
| Export my data | Export (M6) + password protection | above |
| Clear AI cache/history | Privacy & data → *Clear AI history*: AI audit rows + cloud-made embeddings; items/summaries/tags kept | `M7PrivacyTest`, `M7PrivacyFlowTest` |
| View recent cloud processing activity | Privacy & data: last 100 cloud sends — item, what (organizing / Ask answer / meaning index), provider·model, characters sent, time, outcome. Ask and embedding sends are now recorded too (existing `ai_runs` table, no schema change); the text itself is never stored. | `M7PrivacyTest.cloudActivityListsEverySend…` |

No schema change (DB stays v6). Permanent deletion = only *Delete all data*, behind a typed word.

## 2. Accessibility audit

| Check | Method | Result |
|---|---|---|
| Labels for TalkBack, 48 dp targets | `M7AccessibilityTest` walks the semantics tree (what TalkBack reads) on Home, Inbox, Search, Settings, Item (incl. menu), Connections, Tasks, Collections + rule editor, Timeline, Ask, Topics, Capture sheet, Backup, Notion, AI settings, Export: every enabled clickable needs text/description and a ≥48×48 dp touch area. | ✅ 0 problems after fixes |
| Found & fixed | 4 switches announced without a name (Export ×2, collection match-all, rule "pinned") → whole row is now one toggleable 48 dp row; rule-remove ✕ had no label → "Remove rule". | ✅ |
| Large font + small phone | Same audit at **2× font on a 320 dp phone** (`M7AccessibilityLargeFontTest`) + screenshots `docs/screenshots/m7`. Found: Home capture title broke mid-word ("করু / ন") → button moves under the text at large font/narrow screens; bottom-bar labels were cut ("ইনব") → labels capped at 1.3× (TalkBack still reads the full label). | ✅ fixed |
| Text contrast | `ContrastTest`: every text/background pair of both themes and all status chips ≥ 4.5:1 (WCAG AA). | ✅ all pass |
| Bengali/English consistency | `TranslationParityTest`: every string exists in both languages with identical placeholders; lint MissingTranslation clean. | ✅ |
| Real TalkBack | Reading order, focus, gestures with the real screen reader. | ⏳ phone |

## 3. Performance

JVM numbers (Robolectric + real SQLite on the build machine) — **not phone numbers**; they find algorithmic problems. `M7PerformanceTest`:

| Measurement | Before fix | After fix |
|---|---|---|
| Build search index, 5 000 items | 18.7 s | 1.9 s |
| Keyword search, 5 000 items (each keystroke) | **21.0 s** | 0.26 s |
| Meaning search (offline) | 20.7 s | 0.52 s |
| Ask (offline) | 20.6 s | 0.47 s |
| Graph neighbours / timeline (month) / export (5 000 items) | — | 0.96 s / 0.22 s / 1.9 s |
| Capture 300 text items | — | 12.6 ms per item |
| Capture 150 MB file / export it | — | 0.21 s / 0.54 s, heap growth 21 MB |

- **Fixed (critical): search was O(n²).** The FTS4 `item_id` column is not indexed, so finding stale rows (run on every search) scanned the whole index once per item. Index rows now use the item's own rowid; stale/deleted rows are found by rowid. Rows written by the first v6 indexer are repaired automatically (`indexFromEarlierBuildIsRepairedWithoutDuplicates`). No schema change.
- **Fixed: export read each attachment fully into memory** (200 MB video → likely out-of-memory). Now streamed in 64 KiB blocks and hashed on the way.
- Capture already streamed files (200 MB limit); backup encrypts in chunks (M5).
- Battery: background work is WorkManager only — processing per item, Notion sync network-only, backup daily (optionally charging-only), reminders daily and opt-in. No wake locks, no polling.
- ⏳ Pending on the phone: cold-start time (debug + release), search latency with the owner's real library, memory while OCR-ing large photos, battery over a day, storage usage view vs. real files.

## 4. Regression (run in this session)

Round 2 full run (clean build, 2026-11-22, v0.7.0-m7), from the JUnit XML files:

| Suite | Result |
|---|---|
| `:core:notion:test` | 56/56 ✅ |
| `:core:data:testDebugUnitTest` | 100/100 ✅ — incl. MigrationTest v1→v6, M7SecurityTest, M7PerformanceTest (3), M7PrivacyTest (3) |
| `:app:testDebugUnitTest` flow + audit tests | 63/63 ✅ — incl. M7PrivacyFlowTest (5), ShareCaptureTest, M7Accessibility ×2 (now also the Privacy screen), ContrastTest, TranslationParityTest |
| `:app:recordRoborazziDebug` screenshot tests | 47/48 in the full run — new `ScreenshotM7Test.x05` failed: it scrolled to a node the lazy list had not composed yet (test bug). Fixed and re-run: ScreenshotM7Test 5/5 ✅ |
| Lint | `:app` 0 errors (27 warnings), `:core:data` no issues ✅ |
| `assembleDebug` / `assembleRelease` | both OK ✅ (`0.7.0-m7-debug`, `0.7.0-m7` unsigned) |

Round 1 (before the §10.1 work): 56 / 97 / 58 / 47, all passing.

Covered areas: migrations v1–v6; offline capture, processing, queue/restart idempotency; Notion idempotency, conflicts, error codes; backup encryption, integrity, restore + rollback; export, search, chat, graph, collections, reminders opt-in.

## 5. Spec §13.2 manual acceptance checklist

| Item | Automated evidence | Real device |
|---|---|---|
| Share plain text | ShareCaptureTest | ⏳ |
| Chrome URL / YouTube URL / Facebook payload | URL + platform tests (CaptureRepositoryTest, ShareCaptureTest) | ⏳ each real app |
| Gallery image + OCR fallback | image capture tests; OCR = ML Kit | ⏳ |
| PDF import + text extraction | ProcessingTest (PdfBox) | ⏳ |
| Floating overlay permission + disable | settings flow tests | ⏳ |
| Airplane-mode capture, queue later | offline capture + network-constrained WorkManager | ⏳ |
| Force-stop/reboot keeps queued jobs | WorkManager persistence + re-queue on start (tested) | ⏳ |
| Notion keeps existing properties | schema guard / option-safe mapper tests | ⏳ live write |
| Same Capture ID never duplicates a Notion page | `lostCreateResponseNeverDuplicates` | ⏳ live |
| Notion 429/permission keeps local data | Notion error tests | ⏳ live |
| Drive encrypted backup created + verified | BackupRestoreTest (local Drive stub) | ⏳ real Drive |
| Fresh install restore with recovery key | `cleanInstallRestoreBringsBackEverything…` | ⏳ real Drive |
| Attachment hashes match after restore | checksum tests | ⏳ real Drive |
| Cloud AI off → nothing sent | `localOnlyItemsNeverReachTheCloud`, M7SecurityTest | ✅ code; ⏳ network check optional |
| Low confidence → Needs Review | `lowConfidenceGoesToUnsortedNeedsReview` | ⏳ |
| User-edited field not overwritten | `ownerEditsAreKeptWhenAiRunsLater`, `userEditedFieldsAreNeverOverwritten` | ⏳ |
| Search offline | M6/M7 search tests | ⏳ |
| Ask cites sources, avoids unsupported answers | `chatCitesOnlyRealSourcesAndWithholdsLocalOnly` | ⏳ with Gemini |
| Export has database + attachments + manifest | export test: all DB records as JSON + CSV/Markdown + attachments + SHA-256 integrity manifest. The raw SQLite file is in the encrypted Drive backup, not in the export. | ⏳ SAF |

## 6. Spec §13.3 release blockers

| Blocker | Status |
|---|---|
| Any data-loss bug | None known. Failure paths tested (Notion, Drive, restore rollback, export failure deletes only the partial file). |
| Backup restore test fails | Automated restore tests pass; **real Drive restore still ⏳** — cannot be declared clear until done on the phone. |
| Secret/API key in repository | None. Only the old, rotated debug keystore remains in history (⚠️ above). |
| Local-only item sent to cloud AI | Not possible in code paths tested (M7SecurityTest + earlier tests). |
| Notion schema migration damages data | PKOS never changes the Notion schema (guard tests). |
| UI shows fake sync/backup success | Success only after verified API result / upload verification (existing tests). |
| Core capture fails offline | Capture tests run with no network. |

## 7. M7 completion checklist

Pass = verified in this session by an automated test/check · Pending = needs the owner's phone/accounts (steps: [m7-acceptance-guide.md](m7-acceptance-guide.md)) · Fail = none open.

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 1 | No secrets in repo/logs | Pass | history scan; no logging calls |
| 2 | Old debug keystore in history | Pending (owner decision) | [proposal](security-debug-keystore-history.md); Option A by owner, Option B needs approval |
| 3 | Secrets encrypted with Keystore | Pass (code) · Pending (device Keystore) | KeystoreSecretStore |
| 4 | Share target can't read private files | Pass | ShareCaptureTest.refusesFileUris… |
| 5 | HTTPS only / no cleartext | Pass (config) · Pending (device TLS) | network_security_config.xml |
| 6 | Minimal OAuth scope + disconnect/revoke | Pass (code) · Pending (real Google, guide #25) | drive.file; M7PrivacyFlowTest |
| 7 | Local-only never sent to cloud | Pass | M7SecurityTest, ProcessingTest, M6FeaturesTest |
| 8 | Local-only per item | Pass · Pending (guide #21) | KnowledgeRepository.setLocalOnly, item menu |
| 9 | Notion/Drive failure never loses local data | Pass · Pending (guide #12) | Notion + backup failure tests |
| 10 | Export protected when private | Pass · Pending (guide #22) | M7PrivacyTest (AES-256) |
| 11 | §10.1 privacy controls (all 9) | Pass · Pending (guide #16, #23–26) | table 1a |
| 12 | TalkBack labels, 48 dp targets | Pass (semantics audit) · Pending (guide #28) | M7AccessibilityTest |
| 13 | Contrast AA, bn/en parity | Pass | ContrastTest, TranslationParityTest |
| 14 | Large font / small screen | Pass (2× / 320 dp) · Pending (guide #29) | M7AccessibilityLargeFontTest, screenshots m7 |
| 15 | Search/capture/export performance & large files | Pass (JVM) · Pending (guide #30) | M7PerformanceTest |
| 16 | Startup time, memory, battery | Pending | guide #30 |
| 17 | Migrations v1–v6 | Pass | MigrationTest |
| 18 | Full regression, lint, debug + release | Pass | section 4 |
| 19 | §13.2 manual acceptance | Pending | guide #1–#22 |
| 20 | §13.3 release blockers | Pass (automated) · Pending (real Drive restore, guide #14) | section 6 |

**M7 verdict:** the code work and automated checks for M7 are done. **M7 exit criteria are not met yet:** items 2, 16, 19 and the
device parts of the other items stay Pending until the owner runs the acceptance guide. M8 must not start before that.
