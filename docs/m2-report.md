# M2 — Capture core: report

Date: 2026-11-20. Status: **done, all tests pass**.

## Implemented
- **Share target (R-01)** — `ShareActivity` receives `ACTION_SEND` / `ACTION_SEND_MULTIPLE` for text, links, images,
  PDFs and other files. It saves immediately (no Save button), then shows a small card: saved / duplicate / failed
  with reason + Retry, optional note, Pin, Open, Done. Source app taken from `launchedFromPackage`/referrer.
- **In-app capture sheet** — Link, Text, Quick note (typed inside the sheet), Image (Android Photo Picker),
  File (Storage Access Framework), Paste (user-initiated clipboard read only). Snackbar confirms with "Open".
- **Floating button (R-02, optional)** — `FloatingCaptureService` (foreground, `specialUse`) draws a draggable
  bubble that snaps to the edge and opens `QuickCaptureActivity`. Turned on only from Settings after an
  explanation dialog and the system "Display over other apps" page; if not granted, it stays off and says so.
  Persistent notification with Stop. Restored on app start only if the permission is still granted.
- **Save-first storage (R-03, spec 5.2)** — `CaptureRepository`: files streamed into app storage with SHA-256
  (content-addressed, 200 MB limit, low-storage check), then ONE Room transaction writes item + provenance +
  attachments + queued jobs (OCR / EXTRACT / AI_CLASSIFY for M3). Any failure leaves no rows and removes new files.
- **Duplicates** — same content hash or canonical URL → item kept and flagged `duplicate_of_id` (never dropped).
- **Item detail** — pin/unpin, personal note, attachment list (opened via read-only FileProvider grant),
  duplicate hint, "Open original link".
- **DB v2** — `content_hash`, `duplicate_of_id` + indexes via Room AutoMigration 1→2 (verified by MigrationTestHelper).
- Local-only default respected (privacy scope stored per item). No INTERNET permission in M2.

## Tests (all pass)
| Suite | Tests |
|---|---|
| :core:notion (unchanged) | 50/50 |
| :core:data — CaptureRepositoryTest 7, MigrationTest 1, DatabaseTest 6, Settings 1 | 15/15 |
| :app — AppFlowTest 17, ShareCaptureTest 6, StatusMappingTest 3 | 26/26 |
| Screenshots — M1 16, M2 8 (12 images in docs/screenshots/m2) | 24/24 |
| Lint (app + core:data) | No issues |
| assembleDebug + assembleRelease | ✅ |

New capture tests cover: URL share with tracking-param removal + platform detection, image copy + on-disk dedupe,
30 MB streamed file, unreadable file → no rows/no files, empty + low storage errors, local-only + note,
persistence across DB reopen, v1→v2 migration keeping data, share intent parsing (text / multiple / clipData /
invalid), ShareActivity saving text and image without pressing Save, sheet quick note, empty paste, pin + note.

## Limits (honest)
- No emulator in this environment: floating bubble drawing over other apps and real share sheets need a device check.
- Notion sync is unchanged and still not wired to the app (needs owner's token; planned milestone).
