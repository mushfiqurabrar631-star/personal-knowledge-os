# Personal Knowledge OS (PKOS)

Android-first personal knowledge library: share anything → saved on device first →
organized by hybrid AI → encrypted Google Drive backup → synced to the owner's Notion
Master Database. Specification: *Personal Knowledge OS Master Build Specification v1.0*.

## Status
See [CHECKLIST.md](CHECKLIST.md). Done: M1 app shell ([report](docs/m1-report.md)) · M2 capture ([report](docs/m2-report.md)) ·
M3 processing ([report](docs/m3-report.md)) · **M4 Notion integration** ([report](docs/m4-report.md)). Next: M5 encrypted Drive backup.

## Modules
| Module | Type | Purpose |
|---|---|---|
| `app` | Android app | Compose UI, navigation, onboarding, Home/Inbox/Search/Topics/Ask AI/Settings |
| `core/data` | Android library | Room database (local source of truth), capture, processing engine, Notion sync service, settings |
| `core/notion` | Kotlin/JVM | Notion schema guard, option-safe mapper, idempotent crash-safe sync engine, file upload; `testFixtures` FakeNotion |

## Build
Requirements: JDK 17+ (21 used), Android SDK with `platforms;android-37.2` and `build-tools;37.0.0`.

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleDebug                 # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:notion:test :core:data:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:recordRoborazziDebug          # screenshots → docs/screenshots/m1..m4/
```
Offline check of the Notion core only: `./scripts/verify-core-notion.sh`.

## Install on a phone
Enable *Install unknown apps* for your file manager, open `app-debug.apk`. The debug build installs as
**PKOS** with id `com.pkos.app.debug`, so it never conflicts with a later release build.

## Notion setup (owner)
1. notion.so/profile/integrations → New integration → type **Internal**, capabilities: Read, Update, Insert content.
2. Open *My knowledge Hub → Master Database* → ⋯ → Connections → add the integration.
3. In PKOS → Settings → **Notion connection**, type the token and tap *Save and check* (stored encrypted with Android
   Keystore; never paste it in chat, code or GitHub). When the live check shows "Connection works", switch on *Auto sync*.
4. Live verification steps: [docs/m4-report.md](docs/m4-report.md#live-verification-on-your-phone).

Docs: [Notion mapping](docs/notion-mapping.md) · ADRs [0001](docs/adr/0001-notion-target-and-sync.md),
[0002](docs/adr/0002-body-marker-journal-adoption.md), [0003](docs/adr/0003-android-app-foundation.md), [0004](docs/adr/0004-notion-sync-in-app.md) ·
[Notion core test report](docs/test-report.md) · Font licence: [Hind Siliguri OFL](docs/licenses/HindSiliguri-OFL.txt)
