# M6 — Search & Advanced Features (spec §9)

Date: 2026-11-22 · DB schema v5 → **v6** (additive AutoMigration, tested) · no new paid service, no new permission.

## What was built (all real, wired to the database)

| # | Feature | Implementation | Honest limits |
|---|---|---|---|
| 1 | Semantic search | `SearchService`: FTS4 keyword index (title, text, summary, note, OCR/PDF text, tags, category/topic, URL) — always on, offline. “Meaning” layer: bilingual related-word groups (bn↔en) + trigram spelling match (offline) + optional Gemini embeddings (`gemini-embedding-001`, 256 dims) only with cloud AI **and** semantic search on; local-only items are never embedded. Filters: category, status, type, priority, date, pinned, in-Notion. Each result shows *why* it matched. | Offline “meaning” is word-group + spelling based, not true vector semantics. Cloud embeddings need the owner’s Gemini key; not verified against the live API from the sandbox. |
| 2 | Knowledge chat | `KnowledgeChat`: retrieves top 6 own items, answers only from them with `[n]` citations; citations are validated (invalid/missing → answer withheld), `NOT_IN_LIBRARY` → “not found”. Local-only items never enter the cloud prompt (count shown). Offline: extractive answer (best sentence per source, cited). Sources open the item. | Single-turn Q&A (no follow-up memory). Cloud answer path tested with a fake generator only. |
| 3 | Knowledge graph | `GraphService`: per-item edges (category, topic, subtopic, tags, shared tags, same topic, similar text, duplicate, owner links) with reason + confidence + AI/USER origin. Owner can link/unlink items (stored in `knowledge_links`). Connections screen: radial map + explained list. | Per-item view only; no whole-library graph canvas. |
| 4 | Auto topic creation | Processing reuses an existing topic by exact or near-identical name (normalized + trigram ≥0.82); max 30 automatic topics per category; Topics screen suggests merges of near-duplicates, merged only on the owner’s tap, “keep separate” remembered. | — |
| 5 | Smart recommendations | Related items on the item screen and “Next useful” on Home (to-learn / in-progress / pinned, active topics first), each dismissible and remembered. | Rule-based, local. |
| 6 | Knowledge timeline | Day / week / month buckets: saved, owner edits, AI organisation, most-active topics, tappable items. | — |
| 7 | Action extraction | bn/en patterns (checkboxes, TODO:, need to/must, “করতে হবে” …, list verbs, ideas) + `action_items` from cloud AI. Always PROPOSED; becomes a task only on **Confirm**; **no due date is ever inferred** — only the owner can pick one. Tasks screen + item screen. | Tasks live in PKOS only (not pushed to Notion/calendar). |
| 8 | Forgotten-knowledge reminder | **Off by default.** When switched on: daily WorkManager check, one quiet notification for a stale important item, re-reminds the same item only after the owner’s interval (3/7/14/30 days); “Rediscover” on Home. Needs notification permission (requested only when switched on). | Notification delivery must be checked on a real phone. |
| 9 | Smart collections | Manual (add/remove from item screen) and rule-based (category, topic, status, priority, type, platform, tag, pinned, saved-within-days; all/any; “not”), evaluated live. Deleting a collection never deletes items. | — |
| 10 | Export & recovery | One .zip via the system file picker: `pkos-export.json` (all fields, topic path, tags, actions, Notion URL, field origins), `items.csv` (UTF-8 BOM, formula-injection safe), `markdown/` (one file per item), `attachments/`, `integrity-report.json` (SHA-256 of every file, `secrets_included:false`). Local-only items excluded unless the owner opts in. Tokens/keys are not in the DB and are never exported. Integrity check: SQLite `integrity_check`, `foreign_key_check`, every attachment present + SHA-256. Failed export removes the partial file. Encrypted Drive backup/restore (M5) unchanged. | Import of an export zip is not implemented (recovery = M5 encrypted backup/restore). |

## Database (migration safety)
v6 adds only new tables: `knowledge_fts` (FTS4), `embeddings`, `knowledge_links`, `action_items`, `dismissals`. No existing column or row is changed. `MigrationTest.v1ToV6KeepsExistingItems` migrates v1→…→v6 with schema validation and reopens with Room (no destructive fallback). The search index rebuilds itself from existing items.

Found and fixed during regression: SQLite’s `integrity_check` needs write access to validate an FTS4 index — the M5 backup snapshot check (read-only open) would have failed every backup on v6. Snapshot/staging copies are now opened read-write (never the live DB); all 13 backup/restore tests pass again. Also fixed a lint-caught API-35-only call in the timeline.

## Privacy / cost
Cloud calls (answers, embeddings) only with *Cloud AI* on and the owner’s own Gemini key; failures fall back to offline results silently. Notion integration, Drive backup, recovery, and owner-edited fields (field origins) are untouched.

## Tests
- `:core:notion` 56/56 · `:core:data` 93/93 (new `M6FeaturesTest` 12 + migration v6) · `:app` 53/53 (new `M6FlowTest` 7) · screenshots 43/43 (new `ScreenshotM6Test` 7 → 10 images in `docs/screenshots/m6`).
- Lint: 0 errors (app + core:data). `assembleDebug` + `assembleRelease` OK.

## Needs a real phone
1. Reminder notification appears (Android 13+ permission prompt), tap opens the item.
2. Export to Downloads/Drive via the system picker; open CSV in a spreadsheet, Markdown in a notes app.
3. With cloud AI + key: Ask gives cited Gemini answers; meaning search returns “Similar meaning” matches.
4. Still pending from earlier milestones: M2 floating capture, M4 live Notion write, M5 real Drive backup + clean-install restore.
