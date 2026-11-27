# Personal Knowledge OS (PKOS)

Android-first personal knowledge library: share anything → saved on device first →
organized by hybrid AI → encrypted Google Drive backup → synced to the owner's Notion
Master Database. Specification: *Personal Knowledge OS Master Build Specification v1.0*.

## Status
Version **1.0.0** (versionCode 10000) — **release candidate. Development (M1–M8) is done and automated tests pass;
Final Testing on the owner's phone has not started**, so 1.0.0 is not yet declared released.
Plan and Pass/Fail records: [Final Testing Plan](docs/final-testing-plan.md) (`testing/final-testing-results.csv`,
`python3 scripts/testplan.py check|gate`).

| Guide | For |
|---|---|
| [Owner prerequisites](docs/owner-prerequisites.md) | release key, OAuth client, Gradle checksum, phone prep — before Final Testing |
| [Installation guide](docs/installation-guide.md) | which APK, installing, updating |
| [Setup guide](docs/setup-guide.md) | Notion, Google Drive, cloud AI, optional features |
| [A-to-Z user guide](docs/user-guide.md) | every feature |
| [Release signing & OAuth](docs/release-signing-and-oauth.md) | release key, SHA-1, Android OAuth clients |
| [Recovery runbook](docs/recovery-runbook.md) | lost phone, lost keys, failed restore |
| [Reproducible build](docs/reproducible-build.md) | pinned toolchain, verifying an APK |
| [Security & limitations](docs/security-and-limitations.md) | security model, known limits |
| [Changelog](CHANGELOG.md) | what changed |

Development reports: M1 [report](docs/m1-report.md) · M2 [report](docs/m2-report.md) · M3 [report](docs/m3-report.md) ·
M4 [report](docs/m4-report.md) · M5 [report](docs/m5-report.md) ([backup guide](docs/backup-restore-guide.md)) ·
M6 [report](docs/m6-report.md) · M7 [report](docs/m7-report.md) · Phase 1 [report](docs/phase1-report.md) ·
Phase 2 / M8 [report](docs/phase2-report.md) · Phase 3/4 [readiness](docs/phase34-readiness-report.md) · [CHECKLIST](CHECKLIST.md).

## Modules
| Module | Type | Purpose |
|---|---|---|
| `app` | Android app | Compose UI, navigation, onboarding, Home/Inbox/Search/Topics/Ask AI/Settings, M6 tools (Tasks, Collections, Timeline, Connections, Export) |
| `core/data` | Android library | Room database (local source of truth), capture, processing engine, Notion sync service, backup, settings, `features/` (search, chat, graph, topics, recommendations, timeline, actions, collections, reminders, export) |
| `core/notion` | Kotlin/JVM | Notion schema guard, option-safe mapper, idempotent crash-safe sync engine, file upload; `testFixtures` FakeNotion |

## Build
Requirements: JDK 17+ (Temurin 21 used), Android SDK with `platforms;android-37.2` and `build-tools;37.0.0`.

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-{arm64-v8a,armeabi-v7a,x86_64,x86,universal}-debug.apk
./gradlew :app:assembleRelease      # R8-shrunk; unsigned unless PKOS_RELEASE_* is set (see release-signing-and-oauth.md)
./gradlew :app:bundleRelease        # app/build/outputs/bundle/release/app-release.aab (separate run from APK builds)
./scripts/build-signed-release.sh   # owner's computer only: signed APKs + AAB, prints SHA-1/SHA-256
./gradlew :core:notion:test :core:data:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:lintDebug :core:data:lintDebug
./gradlew :app:recordRoborazziDebug # screenshots → docs/screenshots/m1..m7/
./gradlew :app:connectedDebugAndroidTest   # device smoke tests (needs an emulator or phone)
```
`-Ppkos.noAbiSplits=true` builds a single APK per build type.

CI (GitHub Actions, free runners): tests, lint, screenshots, debug/release/R8-check APKs, AAB, plus an emulator job
(API 34) with instrumentation tests and an R8 launch + monkey smoke. Artifacts: `pkos-<run>-debug-apk`,
`pkos-<run>-release-unsigned` (APKs, AAB, mapping), `pkos-<run>-instrumentation-reports`, screenshots and reports.
No signing key or secret is stored in GitHub.

## Install on a phone
See the [installation guide](docs/installation-guide.md). Debug installs as `com.pkos.app.debug`, release as
`com.pkos.app` — they never conflict.

## Notion setup (owner)
1. notion.so/profile/integrations → New integration → type **Internal**, capabilities: Read, Update, Insert content.
2. Open *My knowledge Hub → Master Database* → ⋯ → Connections → add the integration.
3. In PKOS → Settings → **Notion connection**, type the token and tap *Save and check* (stored encrypted with Android
   Keystore; never paste it in chat, code or GitHub). When the live check shows "Connection works", switch on *Auto sync*.
4. Live verification steps: [docs/m4-report.md](docs/m4-report.md#live-verification-on-your-phone).

Docs: [Notion mapping](docs/notion-mapping.md) · ADRs [0001](docs/adr/0001-notion-target-and-sync.md),
[0002](docs/adr/0002-body-marker-journal-adoption.md), [0003](docs/adr/0003-android-app-foundation.md), [0004](docs/adr/0004-notion-sync-in-app.md), [0005](docs/adr/0005-encrypted-drive-backup.md) ·
[Notion core test report](docs/test-report.md) · Font licence: [Hind Siliguri OFL](docs/licenses/HindSiliguri-OFL.txt) · Export encryption: zip4j (Apache-2.0)
