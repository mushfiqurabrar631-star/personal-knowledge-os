# PKOS implementation checklist

Status legend: ✅ done & tested · 🟡 partly done · ⬜ not started · 🔒 blocked

| ID | Requirement | Status | Evidence / note |
|---|---|---|---|
| R-01 | Android Share capture | ⬜ | M2 |
| R-02 | Floating capture | ⬜ | M2 |
| R-03 | Local-first | ⬜ | M2 (Room) |
| R-04 | Hybrid AI | ⬜ | M3 |
| R-05 | Auto organization | ⬜ | M3 |
| R-06 | Notion integration | 🟡 | Schema inspected, Capture ID added & verified, `:core:notion` mapping + idempotent sync, 31 unit tests pass. Pending: OkHttp transport, WorkManager job, Room `notion_mappings`, token setup UI, live test with real token |
| R-07 | Cloud backup | ⬜ | M5 |
| R-08 | Offline support | ⬜ | M2/M3 |
| R-09 | No silent paid usage | ⬜ | M3 |
| R-10 | Export & recovery | ⬜ | M6 |
| R-11 | 10 advanced features | ⬜ | M6 |
| R-12 | Premium UX | ⬜ | M1 |

## Build environment
- 🔒 Android SDK / Google Maven / Maven Central / Gradle distribution are blocked in the
  cloud workspace. Needed domains: `dl.google.com`, `maven.google.com`, `repo1.maven.org`,
  `services.gradle.org`, `plugins.gradle.org`.
- Until then `scripts/verify-core-notion.sh` compiles and tests the pure-Kotlin modules with
  the Kotlin compiler bundled in a local Gradle install (Kotlin 2.0.21).
- Dependency versions in `gradle/libs.versions.toml` are the ones verified locally; bump to
  current stable when the repositories are reachable.

## Last verified checkpoint
2026-11-20 — `scripts/verify-core-notion.sh` → `OK (31 tests)`; mutation check: disabling the
option guard fails 4 tests, removing the Capture ID lookup fails 3 tests.
