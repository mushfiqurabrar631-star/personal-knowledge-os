# Personal Knowledge OS (PKOS)

Android-first personal knowledge library: share anything → saved on device first →
organized by hybrid AI → encrypted Google Drive backup → synced to the owner's Notion
Master Database. Specification: *Personal Knowledge OS Master Build Specification v1.0*.

## Status
See [CHECKLIST.md](CHECKLIST.md). Current milestone: **M1 — App shell** ([report](docs/m1-report.md)).

## Modules
| Module | Type | Purpose |
|---|---|---|
| `app` | Android app | Compose UI, navigation, onboarding, Home/Inbox/Search/Topics/Ask AI/Settings |
| `core/data` | Android library | Room database (local source of truth), DataStore settings, repositories |
| `core/notion` | Kotlin/JVM | Notion schema guard, option-safe mapper, idempotent crash-safe sync engine |

## Build
Requirements: JDK 17+ (21 used), Android SDK with `platforms;android-37.2` and `build-tools;37.0.0`.

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleDebug                 # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:notion:test :core:data:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:recordRoborazziDebug          # screenshots → docs/screenshots/m1/
```
Offline check of the Notion core only: `./scripts/verify-core-notion.sh`.

## Install on a phone
Enable *Install unknown apps* for your file manager, open `app-debug.apk`. The debug build installs as
**PKOS** with id `com.pkos.app.debug`, so it never conflicts with a later release build.

## Notion setup (owner, M4)
1. notion.so/profile/integrations → New integration → type **Internal**, capabilities: Read, Update, Insert content.
2. Open *My knowledge Hub → Master Database* → ⋯ → Connections → add the integration.
3. Paste the token in PKOS → Settings → Integrations (stored encrypted with Android Keystore).
   Never paste the token in chat, code or GitHub.

Docs: [Notion mapping](docs/notion-mapping.md) · ADRs [0001](docs/adr/0001-notion-target-and-sync.md),
[0002](docs/adr/0002-body-marker-journal-adoption.md), [0003](docs/adr/0003-android-app-foundation.md) ·
[Notion core test report](docs/test-report.md) · Font licence: [Hind Siliguri OFL](docs/licenses/HindSiliguri-OFL.txt)
