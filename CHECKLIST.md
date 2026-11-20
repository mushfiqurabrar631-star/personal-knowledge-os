# PKOS implementation checklist

Status legend: ✅ done & tested · 🟡 partly done · ⬜ not started · 🔒 blocked

| ID | Requirement | Status | Evidence / note |
|---|---|---|---|
| R-01 | Android Share capture | ✅ | ShareActivity (SEND/SEND_MULTIPLE), auto-save + confirmation card; ShareCaptureTest 6, CaptureRepositoryTest 7 |
| R-02 | Floating capture | 🟡 | Optional overlay bubble + QuickCaptureActivity, permission flow in Settings; needs on-device check (no emulator) |
| R-03 | Local-first | ✅ | Save-first transaction, content-addressed files, DB v2 AutoMigration tested, no INTERNET permission |
| R-04 | Hybrid AI | ⬜ | M3 |
| R-05 | Auto organization | ⬜ | M3 |
| R-06 | Notion integration | 🟡 | Schema inspected, Capture ID added & verified, `:core:notion` mapping + idempotent, crash-safe sync (body marker, write-ahead journal, adoption review), 50 tests pass (docs/test-report.md). Pending: OkHttp transport, Room `notion_mappings` + `SyncJournal`, WorkManager job (unique per Capture ID), conflict/adoption review UI, token setup UI, file upload, live test with real token |
| R-07 | Cloud backup | ⬜ | M5 |
| R-08 | Offline support | 🟡 | Offline keyword search over Room works and is tested; queues in M2/M3 |
| R-09 | No silent paid usage | 🟡 | Cloud AI defaults OFF (tested); provider adapters in M3 |
| R-10 | Export & recovery | ⬜ | M6 |
| R-11 | 10 advanced features | ⬜ | M6 |
| R-12 | Premium UX | 🟡 | M1 shell done: navigation, onboarding, 10 screens, design system, bn/en, dark mode, large font; 24 screenshots, 17 UI tests |

## Build environment
- Android SDK installed (platforms android-37.2, build-tools 37.0.0); Gradle 9.9.2 wrapper; AGP 9.5.1; Kotlin 2.4.21.
- Maven Central is used via `repo1.maven.org`; large artifacts redirect to `gcp.mirrors.maven.org` (must be reachable).
- No emulator (no KVM): UI verified with Robolectric Compose tests and Roborazzi screenshots; device install by owner.

## Last verified checkpoint
2026-11-20 — M2 capture core: build ✅, lint ✅, :core:notion 50/50, :core:data 15/15, :app 26/26,
screenshots 24/24 (M2: 12 images). Report: docs/m2-report.md.

Earlier: 2026-11-20 — M1 app shell: assembleDebug/Release ✅, lint ✅ (no issues), :core:notion 50/50, :core:data 7/7,
:app UI 17/17, screenshots 16/16 (24 images). Report: docs/m1-report.md. Awaiting owner review before M2.
