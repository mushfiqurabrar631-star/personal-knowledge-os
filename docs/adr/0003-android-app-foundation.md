# ADR 0003 — Android app foundation (M1)

Status: Accepted (2026-11-20)

## Decision
- **Toolchain (latest stable on 2026-11-20):** AGP 9.5.1 with built-in Kotlin 2.4.21, Gradle 9.9.2,
  compileSdk 37.2 (Compose 1.13 requires ≥37.1), targetSdk 37, minSdk 26, Java 17 bytecode, JDK 21 build.
- **Modules:** `:app` (Compose UI, navigation, ViewModels), `:core:data` (Room + DataStore + repositories),
  `:core:notion` (pure Kotlin Notion sync, see ADR 0001/0002).
- **DI:** a small manual `AppContainer` (no reflection, trivially replaced by in-memory fakes in tests).
- **Navigation:** Navigation Compose 2.10 with type-safe `@Serializable` routes. Bottom bar
  Home · Inbox · [Save] · Search · Settings, Save centred (spec 2.3 #7); Topics, Ask AI, Item detail,
  Integrations, Backup & Sync are pushed screens.
- **Design system:** Material 3, one calm teal accent on neutral surfaces, semantic status tones used only for
  state, 4dp grid, 16–20dp card corners, pill chips, 120–220ms motion. Font: Hind Siliguri (SIL OFL 1.1),
  bundled so Bengali + Latin render consistently on every phone.
- **Languages:** Bengali default (`values/`), English (`values-en/`), per-app language via AppCompat
  (`generateLocaleConfig`, `localeFilters = bn, en`).
- **Honest staging:** features from later milestones are visible but disabled and labelled
  `শীঘ্রই · M2` etc. No screen shows fake data or a fake connection. Integrations report NOT_CONNECTED until
  real credentials exist; "Last backup" reads only VERIFIED snapshots.
- **Privacy defaults:** Cloud AI OFF; Android system backup disabled (PKOS uses its own encrypted backup in M5);
  no permissions requested in M1.
- **Repositories:** `maven("https://repo1.maven.org/maven2")` instead of `mavenCentral()` — the same Maven Central,
  reachable in restricted environments.

## Not in M1 (by design)
Share/Floating capture and saving (M2), OCR/AI (M3), Notion connection (M4), Drive backup and recovery key (M5),
semantic search, Ask AI answers, export (M6). Tables `ai_runs`, `relationships`, `saved_searches`,
`recovery_metadata` are added with migrations in the milestones that use them.
