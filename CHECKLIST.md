# PKOS implementation checklist

Status legend: ✅ done & tested · 🟡 partly done · ⬜ not started · 🔒 blocked

| ID | Requirement | Status | Evidence / note |
|---|---|---|---|
| R-01 | Android Share capture | ✅ | ShareActivity (SEND/SEND_MULTIPLE), auto-save + confirmation card; ShareCaptureTest 6, CaptureRepositoryTest 7 |
| R-02 | Floating capture | 🟡 | Optional overlay bubble + QuickCaptureActivity, permission flow in Settings; needs on-device check (no emulator) |
| R-03 | Local-first | ✅ | Save-first transaction, content-addressed files, DB v1→v4 AutoMigrations tested; network only for opt-in cloud AI / Notion |
| R-04 | Hybrid AI | ✅ | Local rules always + optional Gemini (own key, Keystore), strict schema validation, repair retry, audit trail |
| R-05 | Auto organization | ✅ | WorkManager pipeline: extract/PDF/OCR → classify → confidence policy → review UI; 13 engine tests |
| R-06 | Notion integration | 🟡 | Done & tested: OkHttp transport (MockWebServer, full engine over HTTP), Keystore token UI + live read-only check, Room mappings/journal/conflicts (DB v4), WorkManager sync (unique per item, network-only, retries), partial PATCH, conflict & adoption review UI, File Upload API (≤5 MB). Live schema re-read 2026-11-20 via Notion connector: 12/12 fields match. **Pending owner step: live write test on the phone with the real token** (api.notion.com is not reachable from the build sandbox) |
| R-07 | Cloud backup | ⬜ | M5 |
| R-08 | Offline support | 🟡 | Capture, extraction, OCR, local classification, search offline (tested); Notion queue waits for network (WorkManager constraint); backup queue in M5 |
| R-09 | No silent paid usage | ✅ | Cloud AI off by default, owner key only, quota/429 → local fallback, never paid |
| R-10 | Export & recovery | ⬜ | M6 |
| R-11 | 10 advanced features | ⬜ | M6 |
| R-12 | Premium UX | 🟡 | M1 shell done: navigation, onboarding, 10 screens, design system, bn/en, dark mode, large font; 24 screenshots, 17 UI tests |

## Build environment
- Android SDK installed (platforms android-37.2, build-tools 37.0.0); Gradle 9.9.2 wrapper; AGP 9.5.1; Kotlin 2.4.21.
- Maven Central is used via `repo1.maven.org`; large artifacts redirect to `gcp.mirrors.maven.org` (must be reachable).
- No emulator (no KVM): UI verified with Robolectric Compose tests and Roborazzi screenshots; device install by owner.

## Last verified checkpoint
2026-11-20 — M4 Notion integration: build ✅, lint ✅, :core:notion 56/56, :core:data 54/54, :app 38/38,
screenshots 33/33 (M4: 5 images). Report: docs/m4-report.md. Live phone test pending (owner).

2026-11-20 — M3 processing: build ✅, lint ✅, :core:notion 50/50, :core:data 42/42, :app 29/29,
screenshots 29/29 (M3: 8 images). Report: docs/m3-report.md.

2026-11-20 — M2 capture core: build ✅, lint ✅, :core:notion 50/50, :core:data 15/15, :app 26/26,
screenshots 24/24 (M2: 12 images). Report: docs/m2-report.md.

Earlier: 2026-11-20 — M1 app shell: assembleDebug/Release ✅, lint ✅ (no issues), :core:notion 50/50, :core:data 7/7,
:app UI 17/17, screenshots 16/16 (24 images). Report: docs/m1-report.md. Awaiting owner review before M2.
