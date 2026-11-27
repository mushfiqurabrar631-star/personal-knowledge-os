# Changelog

All notable changes to PKOS. Dates are Asia/Dhaka. "Verified on phone" is stated only where it was actually done —
so far nothing in 1.0.0 is verified on the owner's phone (Final Testing pending).

## [1.0.0] — 2026-11-27 (release candidate, Final Testing pending)
### Added — M8 release engineering
- Release signing from environment variables or git-ignored `local.properties` (owner's own key; never in Git).
- R8 code shrinking and resource shrinking with keep rules; `mapping.txt` per build.
- ABI-split APKs (arm64-v8a, armeabi-v7a, x86_64, x86) + universal APK; Android App Bundle (`bundleRelease`).
- `r8check` CI build type and device smoke tests (`androidTest`): real Keystore, SQLite search, Tesseract native, launch.
- CI: emulator job (API 34) running instrumentation tests and an R8 launch + monkey smoke; AAB and mapping artifacts.
- `scripts/build-signed-release.sh`; docs: installation, setup, A-to-Z user guide, release signing & OAuth,
  recovery runbook, reproducible build, security & limitations, Final Testing Plan with Pass/Fail records.
- versionName `1.0.0`, versionCode `10000`.

### Added — Phase 1 (M1–M7 gaps)
- Per-item BACKED_UP / SYNCED states; search result card (topic path · source · date) and topic/source/backed-up filters.
- AI schema: keywords, entities, related items, action items; sensitive-data guard (local-only before any cloud step).
- Offline Bengali OCR (Tesseract) merged with ML Kit; scanned-PDF OCR; opt-in link previews.
- Incremental encrypted attachment backup (backup format 2; format 1 still restores).
- Opt-in large Notion files as owner-only Drive links (no Notion schema change).
- OpenAI-compatible AI provider alongside Gemini (cloud AI still off by default).
- Library-wide Knowledge map (projects, topics, concepts).
- Database v7 (AutoMigration 6→7).

## [0.7.0-m7] — 2026-11-22
Hardening: privacy controls (spec 10.1), Drive revoke, AES export, accessibility audits, performance fixes. DB v6.

## [0.6.0-m6] — 2026-11-22
Search and the 10 advanced features (meaning search, cited Ask, graph, topics, recommendations, timeline, tasks,
reminders, smart collections, export + integrity check).

## [0.5.0-m5] and earlier
M5 encrypted Google Drive backup and restore · M4 Notion integration · M3 processing pipeline · M2 capture · M1 app shell.
Reports: `docs/m1-report.md` … `docs/m7-report.md`.
