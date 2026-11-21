# M7 — Hardening (security, accessibility, performance, regression)

Date: 2026-11-22 · Version 0.6.0-m6 → **0.7.0-m7** · DB schema stays **v6** (no migration in M7) · no new dependency.
No feature was added. Notion schema, Drive backup and recovery code were not changed.

Legend: ✅ verified by an automated test or a check run in this session · ⏳ **Pending** (needs the real phone, an account or credentials) · ⚠️ open gap

## 1. Security audit

| Area | Finding | Result |
|---|---|---|
| Secrets in Git | Full history scanned (Gemini `AIza…`, Notion `ntn_/secret_`, Google `ya29.`, private keys, keystores, `local.properties`). No API key, token or recovery key was ever committed. `.gitignore` covers `local.properties`, `*.keystore`, `*.jks`, `*.p12`, `keystore/`, `signing.properties`, `secrets.properties`. | ✅ |
| Old debug keystore | `keystore/debug.keystore` (password `android`) is still in **old commits** (removed + rotated in M5). The current debug key is local and untracked; the old key signs nothing. Removing it from history requires a force-push rewrite — owner decision, not done. | ⚠️ documented |
| Logs | No `Log.*`, `println`, `printStackTrace` or HTTP logging interceptor in app/core main code. Tokens, prompts, text and recovery keys are never logged. | ✅ |
| Secrets at rest | Notion token, Gemini key and backup KEK: AES-256-GCM with a non-exportable Android Keystore key; only ciphertext in private prefs; excluded from Android backup (`allowBackup=false`, extraction rules exclude everything). | ✅ code review; Keystore behaviour ⏳ phone |
| Local DB / attachments | App-private storage; FileProvider exposes only `files/attachments/`, not exported, per-intent read grant. | ✅ |
| **Share target accepted `file://` URIs (fixed)** | `ShareActivity` is exported; a malicious app could share `file:///data/data/com.pkos.app/databases/pkos.db` (or the secrets file) and PKOS would read its own private file and save it as an attachment (which could then go to Notion/Drive). Now only `content://` URIs are accepted. Test: `ShareCaptureTest.refusesFileUrisPointingAtPrivateAppFiles` (DB file, secrets file, attachment path; parser + real ShareActivity → no attachment). Share tests now use a real `content://` provider. | ✅ fixed |
| Network | All endpoints are hard-coded `https://` (Notion, Drive, Gemini). Added `network_security_config.xml`: cleartext **off**, system CAs only (user-installed CAs not trusted). | ✅ config; ⏳ phone TLS check |
| OAuth | Drive uses only `drive.file` (files PKOS created). Disconnect stops all Drive use but does **not** revoke the Google grant (the owner can revoke at myaccount.google.com/permissions). | ⚠️ revoke missing (spec §10) |
| Local-only → cloud (release blocker) | Verified at every path: processing (cloud AI), embeddings, Ask prompt, Notion sync. New `M7SecurityTest.localOnlyNeverLeavesThePhone` uses spy providers and also covers switching an item to local-only later (its stored cloud embedding is deleted, nothing is sent afterwards). | ✅ |
| **No per-item local-only switch (fixed)** | Spec §10 requires "local-only per item"; only a global default existed. Item ⋮ menu now has *Keep on this phone only / Allow cloud AI and Notion*; detail shows the privacy state. Pages already in Notion are left untouched. | ✅ fixed (UI ⏳ phone) |
| Notion/Drive failures vs local data | Existing tests: Notion 401/403/404/409/429/500, lost responses, conflicts; Drive auth loss, full Drive, interrupted upload, checksum mismatch, wrong key, failed restore rollback — none deletes or changes local items. | ✅ (re-run below) |
| Export privacy | Export is a plain (unencrypted) ZIP by design; local-only items are excluded unless the owner opts in. Spec §10 says "protect archive if it contains private material": a red warning now appears when local-only items are included. The ZIP itself is still not encrypted. | ⚠️ warning only |

Open spec gaps found by the audit (spec §10.1 privacy controls, not built in M1–M6, **not** added in M7 because M7 adds no features): *Clear AI cache/history*, *View recent cloud processing activity* (the audit table `ai_runs` exists, no screen), *Delete local cache vs delete all data* explanation, *Drive revoke*. Owner decision needed on when to build them.

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

Final full run: clean build, 2026-11-22 ~04:15 (+06), code identical to the commit except `versionName` (0.6.0-m6 → 0.7.0-m7 changed afterwards; lint + debug + release were re-run with 0.7.0-m7 and pass). Figures from the JUnit XML files:

| Suite | Result |
|---|---|
| `:core:notion:test` | 56/56 ✅ |
| `:core:data:testDebugUnitTest` | 97/97 ✅ — incl. MigrationTest v1→v6, M7SecurityTest, M7PerformanceTest (3) |
| `:app:testDebugUnitTest` flow + audit tests | 58/58 ✅ — incl. ShareCaptureTest (7), M7AccessibilityTest ×2, ContrastTest, TranslationParityTest |
| `:app:recordRoborazziDebug` screenshot tests | 47/47 ✅ — incl. ScreenshotM7Test (4) |
| Lint (`:app`, `:core:data`) | 0 errors ✅ (app: 24 warnings) |
| `assembleDebug` / `assembleRelease` | both OK ✅ |

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

**M7 verdict:** automated hardening complete; **acceptance checklist not passed yet** — every ⏳ item needs the owner's phone, Notion token and Google account. M8 should start only after those checks.
