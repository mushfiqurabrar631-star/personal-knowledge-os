# PKOS implementation checklist

Status legend: ✅ done & tested · 🟡 partly done · ⬜ not started · 🔒 blocked

| ID | Requirement | Status | Evidence / note |
|---|---|---|---|
| R-01 | Android Share capture | ✅ | ShareActivity (SEND/SEND_MULTIPLE), auto-save + confirmation card; ShareCaptureTest 6, CaptureRepositoryTest 7 |
| R-02 | Floating capture | 🟡 | Optional overlay bubble + QuickCaptureActivity, permission flow in Settings; needs on-device check (no emulator) |
| R-03 | Local-first | ✅ | Save-first transaction, content-addressed files, DB v1→v4 AutoMigrations tested; network only for opt-in cloud AI / Notion |
| R-04 | Hybrid AI | ✅ | Local rules always + optional Gemini (own key, Keystore), strict schema validation, repair retry, audit trail |
| R-05 | Auto organization | ✅ | WorkManager pipeline: extract/PDF/OCR → classify → confidence policy → review UI; Topic/Subtopic saved, fuzzy duplicates, owner edit/archive/delete (docs/m3-gaps-report.md) |
| R-06 | Notion integration | 🟡 | Done & tested: OkHttp transport (MockWebServer, full engine over HTTP), Keystore token UI + live read-only check, Room mappings/journal/conflicts (DB v4), WorkManager sync (unique per item, network-only, retries), partial PATCH, conflict & adoption review UI, File Upload API (≤5 MB). Live schema re-read 2026-11-20 via Notion connector: 12/12 fields match. **Pending owner step: live write test on the phone with the real token** (api.notion.com is not reachable from the build sandbox) |
| R-07 | Cloud backup | 🟡 | Built & tested: drive.file OAuth, visible folder, AES-256-GCM chunked archive (random nonces), PBKDF2 600k recovery key, consistent DB snapshot, SHA-256 checksums, manifest/versions, resumable upload, size+MD5 verification, GFS retention, quota warning, staged restore + preview + safety snapshot/rollback, WorkManager. **Pending: real Google Drive backup + clean-install restore on the phone** |
| R-08 | Offline support | 🟡 | Capture, extraction, OCR, local classification, search offline (tested); Notion queue waits for network (WorkManager constraint); backup queue in M5 |
| R-09 | No silent paid usage | ✅ | Cloud AI off by default, owner key only, quota/429 → local fallback, never paid |
| R-10 | Export & recovery | 🟡 | M6: zip export (JSON/CSV/Markdown/attachments + SHA-256 report, local-only opt-in, no secrets) + local integrity check, tested; recovery = M5 encrypted restore. No export-zip import. Real-phone file-picker check pending |
| R-11 | 10 advanced features | 🟡 | M6: all 10 implemented & tested (docs/m6-report.md). Limits: offline “meaning” = related words + spelling (vectors only with cloud key), graph is per-item, chat single-turn; reminders, SAF export and Gemini paths not yet verified on a phone |
| R-12 | Premium UX | 🟡 | M1–M7: design system, bn/en parity test, dark mode, WCAG AA contrast test, 48 dp + label audit on all main screens, 2× font on 320 dp audit; real TalkBack check pending |

## Build environment
- Android SDK installed (platforms android-37.2, build-tools 37.0.0); Gradle 9.9.2 wrapper; AGP 9.5.1; Kotlin 2.4.21.
- Maven Central is used via `repo1.maven.org`; large artifacts redirect to `gcp.mirrors.maven.org` (must be reachable).
- No emulator (no KVM): UI verified with Robolectric Compose tests and Roborazzi screenshots; device install by owner.

## Last verified checkpoint
2026-11-22 — M7 round 2 (§10.1 privacy controls, Drive revoke, AES export): :core:notion 56/56, :core:data 100/100, :app 63/63,
screenshots 48/48 (47 in full run + fixed x05 re-run 5/5), lint 0 errors, debug + release OK. M7 exit Pending phone checks (docs/m7-acceptance-guide.md).

2026-11-22 — M7 hardening (v0.7.0-m7, DB v6): :core:notion 56/56, :core:data 97/97, :app 58/58, screenshots 47/47, lint 0 errors,
debug + release OK. Fixed: share target file:// read, O(n²) search index, export memory, per-item local-only, a11y labels/large font.
Acceptance checklist (spec 13.2) pending on the phone. Report: docs/m7-report.md.

2026-11-22 02:58 (+06) — M6 verification, v0.6.0-m6, clean build: :core:notion 56/56, :core:data 93/93 (incl. migration v1→v6),
:app 53/53, screenshots 43/43, lint 0 errors, assembleDebug + assembleRelease OK. Real-device M6 checks not done yet (docs/m6-report.md §3).

2026-11-22 — M6 search & advanced features (DB v6): :core:notion 56/56, :core:data 93/93, :app 53/53, screenshots 43/43
(M6: 10 images), lint 0 errors, debug + release build. Report: docs/m6-report.md.

2026-11-22 — M3 gaps closed: :core:notion 56/56, :core:data 81/81, :app 46/46, screenshots 36/36, lint 0 errors,
debug + release build. Report: docs/m3-gaps-report.md.

2026-11-21 — M5 backup: build ✅, lint ✅ (warnings only), :core:notion 56/56, :core:data 73/73, :app 44/44,
screenshots 36/36 (M5: 4 images). Report: docs/m5-report.md. Real-Drive verification pending (owner).

2026-11-20 — M4 Notion integration: build ✅, lint ✅, :core:notion 56/56, :core:data 54/54, :app 38/38,
screenshots 33/33 (M4: 5 images). Report: docs/m4-report.md. Live phone test pending (owner).

2026-11-20 — M3 processing: build ✅, lint ✅, :core:notion 50/50, :core:data 42/42, :app 29/29,
screenshots 29/29 (M3: 8 images). Report: docs/m3-report.md.

2026-11-20 — M2 capture core: build ✅, lint ✅, :core:notion 50/50, :core:data 15/15, :app 26/26,
screenshots 24/24 (M2: 12 images). Report: docs/m2-report.md.

Earlier: 2026-11-20 — M1 app shell: assembleDebug/Release ✅, lint ✅ (no issues), :core:notion 50/50, :core:data 7/7,
:app UI 17/17, screenshots 16/16 (24 images). Report: docs/m1-report.md. Awaiting owner review before M2.
