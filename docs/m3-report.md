# M3 — Processing & organisation: report

Date: 2026-11-20. Status: **done, all tests pass**.

## Implemented
- **Background pipeline (spec 4.1 / 5.2)** — every capture starts a WorkManager job (unique per item, survives app
  death/reboot; unfinished jobs are re-queued on app start). `ProcessingEngine`: extract → OCR → classify →
  validate → persist, idempotent, with per-job attempts and back-off.
- **Text extraction** — plain-text formats directly; PDFs on device with PdfBox-Android (first 50 pages).
  Unsupported types are marked "unsupported", not failed.
- **OCR** — ML Kit on-device text recognition (bundled model, offline, nothing uploaded). Read errors retry 3×,
  then the file is marked failed and the item is still organised from its other text.
- **Local classifier (always on, offline)** — Bengali + English keyword rules → starter categories, content type,
  purpose, tags, extractive summary (the owner's own sentences). Confidence capped at 0.80, so local rules alone
  never claim "high". Health is never inferred.
- **Optional cloud AI (R-04, R-09)** — Gemini via the owner's own free key, off by default. The key is AES-GCM
  encrypted with an Android Keystore key and excluded from backups. Only title, text (≤6000 chars), link and
  platform are sent; files, notes and local-only items never are. The key travels in a header, never in the URL.
- **Strict output validation (spec 4.4 / 11)** — JSON schema checks (types, enums, 0–1 or 0–100 confidence,
  allowed purposes/priorities/status). Malformed output → one repair retry → then the local result plus a
  "please review" reason. Invented categories are never accepted.
- **Confidence policy (spec 4.5)** — ≥0.85 organised; 0.60–0.84 organised and labelled "Organized by AI";
  <0.60 → Unsorted / Needs review. Fields the owner set (origin USER) are never overwritten; the original text
  and files are never changed. Each AI change writes an item version.
- **Rate limits / quota** — 429 is retried later (Retry-After respected, ≥30 s); after 5 tries, or when the daily
  quota/key is the problem, the item is organised on the phone and says why. Nothing is charged or lost.
- **Audit trail** — `ai_runs` table (provider, model, prompt version, output JSON, confidence, outcome).
- **Review UI** — item detail shows who organised it and how sure it was; "Looks right" and "Change category"
  (owner's choice is final). Files show text-read status and the extracted text.
- **AI settings screen** — cloud AI switch, key save/remove, model, plain-language data rules; Integrations shows
  the AI provider as connected only when a key is saved.
- **DB v3** — `ai_runs` table + `attachments.extracted_text` via AutoMigration (1→2→3 verified).

## Tests (all pass)
| Suite | Tests |
|---|---|
| :core:notion (unchanged) | 50/50 |
| :core:data — ProcessingEngine 13, AiResultParser 5, LocalClassifier 6, GeminiProvider 3, Capture 7, Migration 1, Database 6, Settings 1 | 42/42 |
| :app — ProcessingFlow 3, AppFlow 17, ShareCapture 6, StatusMapping 3 | 29/29 |
| Screenshots — M1 16, M2 8, M3 5 (8 images in docs/screenshots/m3) | 29/29 |
| Lint (app + core:data) | passes (only library/advisory warnings) |
| assembleDebug + assembleRelease | ✅ |

## Limits (honest)
- ML Kit's bundled model reads Latin script only: Bengali screenshots give little or no OCR text.
- Scanned PDFs without a text layer give no text (no PDF OCR yet).
- Gemini was not called live here (no key in this environment); the HTTP contract is covered by tests with
  recorded status codes. The default model name is editable in AI settings.
- APK size grew (debug ≈76 MB, release unsigned ≈68 MB) because of the bundled OCR model and PdfBox; ABI
  splits / R8 shrinking are planned for the release milestone.
- Notion sync remains unwired (needs the owner's token; next milestone).
