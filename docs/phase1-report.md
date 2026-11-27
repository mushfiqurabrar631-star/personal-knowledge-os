# Phase 1 report — M1–M7 development gaps (2026-11-27)

Scope: the 10 gaps found in the 2026-11-26 repository audit, implemented with the owner's 5 decisions of 2026-11-27.
**Status: development done and automated tests pass. Nothing here is verified on a real phone yet** (phone tests are
deliberately not started; they belong to Phase 4 Final Testing). No Notion property was added, removed or renamed;
Google OAuth and signing configuration were not changed; no paid service is used.

| # | Gap (spec) | What was built | Automated evidence | Still needs (Phase 4) |
|---|---|---|---|---|
| 1 | Per-item sync state (8.5) | BACKED_UP / SYNCED states; set after a verified backup; DB trigger reverts on edit | P1SyncStateTest | real backup + Notion |
| 2 | Search result card & filters (9.1) | topic path · platform · date line; topic / source / backed-up filters; keywords, entities, link text indexed | M6FlowTest, P1EnrichmentTest | phone UI |
| 3 | AI schema (4.4) | keywords, entities, related items (AI links, dismissible), action items as text or objects (dates ignored) | P1EnrichmentTest | real Gemini key |
| 4 | Sensitive-data check (4.1 #13) | card (Luhn), OTP, password, secret key, NID, bank account → item made "local only", cloud skipped; owner's own choice wins | P1EnrichmentTest | — |
| 5 | Bengali OCR (3) — decision 1 | Tesseract `ben` model on the device (offline, SHA-256 checked), merged with ML Kit; scanned PDF OCR (≤10 pages) | OcrMergeTest, P1LinkOcrTest | accuracy on real photos |
| 6 | Link metadata (3) — decision 2 | opt-in toggle, **off by default**; HTTPS only, 512 KB cap, YouTube oEmbed; never for local-only items | P1LinkOcrTest | real network |
| 7 | Incremental attachment backup (8.1) | backup format 2: each file one encrypted blob, uploaded once, resumable, verified; orphan clean-up; format 1 still restores | BackupRestoreTest 19 | real Drive backup + clean-install restore |
| 8 | Large Notion files (7.4) — decision 4 | opt-in, **off by default**; files over 5 MB go to the owner's Drive folder "Personal Knowledge OS Files" (not encrypted, sharing never changed) and the existing Files & media property gets the link | NotionSyncServiceTest 15, NotionFlowTest | real Notion + Drive |
| 9 | Second AI provider (4.6) — decision 3 | OpenAI-compatible endpoint (https only, model, optional key in Keystore), cost warning, 402/quota stop; cloud AI still **off by default**; embeddings stay Gemini-only | P1CompatProviderTest, ProcessingFlowTest | owner's real endpoint |
| 10 | Library-wide graph (9 #3) | Knowledge map: projects (main categories), topics, concepts (tags/keywords in 2+ items), bridges between projects, co-occurrence, cross-project links | M6FeaturesTest, M6FlowTest | phone UI / TalkBack |

Database: v7 (AutoMigration 6→7, tested v1→v7 with data). Release signing (decision 5) is Phase 2 work.

## Full regression (clean build, 2026-11-27)
`:core:notion` 56/56 · `:core:data` 122/122 · `:app` 68/68 · screenshots 48/48 · lint 0 errors (32 warnings) ·
assembleDebug + assembleRelease OK. Three M7 large-font screenshots were re-recorded because the screens changed (new search
filters and the Knowledge map row).

## Known limits (honest)
- Tesseract model is the 3.04 `ben` file (15.5 MB, adds to APK size); recognition quality on real photos is not measured.
- The Drive link for large Notion files opens only for the owner's Google account.
- With the compatible provider, meaning search uses the offline layer only.
- GitHub push is blocked (HTTP 403): commits are saved locally only until write access is re-authorized.
