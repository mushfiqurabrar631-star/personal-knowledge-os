# PKOS implementation checklist

Status legend: ✅ done & tested · 🟡 partly done · ⬜ not started · 🔒 blocked

| ID | Requirement | Status | Evidence / note |
|---|---|---|---|
| R-01 | Android Share capture | ⬜ | M2 |
| R-02 | Floating capture | ⬜ | M2 |
| R-03 | Local-first | ⬜ | M2 (Room) |
| R-04 | Hybrid AI | ⬜ | M3 |
| R-05 | Auto organization | ⬜ | M3 |
| R-06 | Notion integration | 🟡 | Schema inspected, Capture ID added & verified, `:core:notion` mapping + idempotent, crash-safe sync (body marker, write-ahead journal, adoption review), 50 tests pass (docs/test-report.md). Pending: OkHttp transport, Room `notion_mappings` + `SyncJournal`, WorkManager job (unique per Capture ID), conflict/adoption review UI, token setup UI, file upload, live test with real token |
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
2026-11-20 (review fixes) — `scripts/verify-core-notion.sh` → 50/50 pass. Mutation check: 6 protections broken one at a
time, each caught by 1–5 tests. Notion read-only check: schema unchanged, 15 rows, 0 with Capture ID.
