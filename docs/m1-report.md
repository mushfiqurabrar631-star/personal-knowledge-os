# M1 — App shell: build, test and screenshot report

Date: 2026-11-20 · Version: `0.1.0-m1` · Spec milestone M1: *Compose navigation, theme, onboarding, home skeleton —
exit: builds on target device; UI states defined.*

## Result
| Check | Command | Result |
|---|---|---|
| Debug APK | `./gradlew :app:assembleDebug` | ✅ `app-debug.apk` (`com.pkos.app.debug`) |
| Release build (unsigned) | `./gradlew :app:assembleRelease` | ✅ |
| Lint | `./gradlew :app:lintDebug :core:data:lintDebug` | ✅ No issues found (both modules) |
| Notion core tests | `./gradlew :core:notion:test` | ✅ 50 / 50 |
| Data layer tests (Room + DataStore, Robolectric) | `./gradlew :core:data:testDebugUnitTest` | ✅ 7 / 7 |
| UI + navigation tests (Compose, Robolectric) | `./gradlew :app:testDebugUnitTest --tests '*AppFlowTest' --tests '*StatusMappingTest'` | ✅ 17 / 17 |
| Screenshots (Roborazzi, native graphics) | `./gradlew :app:recordRoborazziDebug` | ✅ 16 / 16 tests, 24 images in `docs/screenshots/m1/` |

**Not verified here:** installation on a physical phone. No emulator can run in this environment (no KVM).
The APK is attached for you to install; see "Device test steps" below.

## What M1 contains
| Spec item | Where | Notes |
|---|---|---|
| Navigation (2.2, 2.3 #7) | `navigation/PkosApp.kt` | Bottom bar Home · Inbox · **Save** (centre) · Search · Settings; Topics, Ask AI, Item detail, Integrations, Backup & Sync as pushed screens; type-safe routes |
| Onboarding (2.7) | `feature/onboarding` | What it does · local-first · permissions only when needed; skip; remembered in DataStore |
| Home hierarchy (2.3) | `feature/home` | App identity + sync/backup status + settings · search · capture CTA + Share explanation · status summary (saved / needs review / pending sync / last verified backup) · continue where you left off · suggested collections (shown only when they exist) · explore Topics / Ask AI |
| Capture sheet (2.4, 2.7) | `feature/capture` | Link, Text, Screenshot/Image, File, Quick note, Paste — visible but **disabled and labelled “M2”** |
| Inbox states (2.5) | `feature/inbox` | Filters All / Organizing / Needs review / Problems / Sync pending with live counts and per-filter empty states |
| Search (2.7, R-08) | `feature/search` | Real offline keyword search over Room (title, text, summary, link, note); Bengali works; semantic toggle disabled “M6”; no stale “0 results” while typing |
| Topics (2.7) | `feature/topics` | Starter taxonomy (spec 4.3) seeded idempotently, localized names, item/topic counts |
| Ask AI (2.7) | `feature/ask` | Explains grounded, cited answers; honest “AI not set up” state; input disabled |
| Item detail (2.7) | `feature/item` | Status, summary, organization, original text, source, history — from the database |
| Settings (2.7, 10.1) | `feature/settings` | Theme (system/light/dark), language (system/বাংলা/English), Cloud AI (default **off**), local-only default, permission status (read-only), real storage usage, version, replay onboarding |
| Integrations / Backup & Sync (2.7) | `feature/settings` | Notion, Drive, AI all **Not connected**; last *verified* backup only; real queue counts; actions disabled “M5” |
| UI states defined | `ui/status/StatusUi.kt` | Saved on device · Organizing · Needs review · Backing up · Backed up · Sending to Notion · In Notion · Synced · Will retry · Action required; app health never says “up to date” without a destination |
| Design tokens (2.6) | `ui/theme` | M3, one teal accent, semantic status tones, 4dp grid, 16–20dp cards, pill chips, 120–220ms motion, Hind Siliguri (Bengali + Latin) |
| Accessibility (2.1) | throughout | ≥48dp targets (tested at 2× font), icon + text for every status, headings, content descriptions, large-font and small-phone screenshots |
| Data foundation (6) | `core/data` | Room v1 schema exported: knowledge_items, categories, topics, tags, knowledge_tags, attachments, capture_sources, processing_jobs, sync_jobs, notion_mappings, notion_write_intents, backup_snapshots, collections, collection_items, item_versions |
| Privacy | manifest | No permissions; Android system backup disabled (own encrypted backup in M5) |

Nothing in the shipped app uses mock data. Screens “with items” in the screenshots use rows inserted into the
test database by `Fixtures` (test code only), because saving arrives in M2.

## New tests
**`:core:data` (7)** — empty DB reports zero everywhere · counts and inbox filters follow lifecycle states (soft-deleted
excluded) · search is literal (`%`, `_`) and finds Bengali · starter taxonomy is idempotent and never overwrites edits ·
only VERIFIED backups count as “last backup” · recent ordering · settings defaults (Cloud AI off) and persistence.

**`:app` StatusMappingTest (3)** — every processing × sync combination maps to one label · problems outrank everything ·
health never claims “up to date” without a connected destination.

**`:app` AppFlowTest (14)** — onboarding → home and remembered · skip onboarding · bottom navigation reaches every tab ·
home search opens Search · capture sheet lists all six sources, all disabled, nothing saved · empty home shows honest
empty + local-only states · home updates live from the DB · inbox filters show only matching items · offline search
(Bengali) + no-results state · item detail opens and records last-opened · Topics and Ask AI reachable from Home ·
theme and Cloud AI choices persist · Integrations/Backup never claim a connection · 2× font keeps key actions ≥48dp.

## Screenshots (`docs/screenshots/m1/`)
01–03 onboarding · 04/05 home empty light/dark · 06/06b/07 home with items + scrolled · 08 home dark with items ·
09 capture sheet · 10/11 inbox + empty filter · 12/13 search start + results · 14 topics · 15 Ask AI · 16 item detail ·
17/18 settings · 19 integrations · 20 backup & sync · 21 large font (1.6×) · 22 English · 23 small phone (320dp).

## Issues found and fixed during M1
- Bottom bar filled the whole screen (centre button used `fillMaxHeight`) — caught by the first screenshot; fixed.
- Status tiles were too tall, pushing recent items below the fold in Bengali — made compact.
- Search briefly showed “0 results” while debouncing — now shows a progress bar.
- “1 items” in English — plurals added. Squashed pin icon — fixed.
- `PkosDatabase` exposed Room types without `api` dependency — fixed.
- One flaky wait in an inbox test (counts not yet loaded) — test now waits for the count.

## Known limitations
- Physical-device install not verified by me (no emulator here).
- `rememberModalBottomSheetState` is deprecated in Material3 1.5; kept (suppressed) until its replacement is stable.
- Debug APK is large (~22 MB) mainly from `material-icons-extended`; release shrinking is enabled only after it is
  tested (spec 5.3), planned for M7.
- GitHub push from this session is refused until the Claude GitHub App is installed on the repository.

## Device test steps (please run)
1. Install `app-debug.apk` (allow “Install unknown apps” for your file manager).
2. First launch: splash → 3 onboarding pages → *শুরু করুন* → Home. Relaunch: goes straight to Home.
3. Home: search field opens Search; *সেভ করুন* and the centre + open the capture sheet (options disabled, “M2”).
4. Tap the cloud icon → Backup & Sync shows “No backup yet / only on this phone”.
5. Settings: switch theme Light/Dark; switch language বাংলা ↔ English (app restarts its screen in the new language).
6. Settings → Integrations: all three “Not connected”.
7. Set system font to largest and display size to largest: nothing overlaps; every button stays tappable.
8. Turn on airplane mode: the app behaves the same (nothing in M1 needs the network).
