# M3 gap closure — report (2026-11-22)

Closes the M3 items found in the core audit. No database migration was needed (schema v5 already had
`topic_id`, `subtopic_id`, `topics.parent_topic_id`, `field_origins`, `deleted_at`).

## Implemented
- **Topic & Subtopic (R-05):** cloud AI `topic`/`subtopic` are validated and saved; an existing topic with the same
  name (case-insensitive) in the category is reused, otherwise created (subtopic = child topic). Only for organised items
  (confidence ≥ 0.60, real category); never when the owner set the topic. Local rules invent no topic. Item detail
  shows "Topic › Subtopic" and the status.
- **Fuzzy duplicates (spec 4.1 step 10):** after content-hash and canonical-URL checks, character-3-gram Jaccard
  similarity (≥ 0.85, texts ≥ 40 chars, latest 300 items) on normalised text (Bengali + English, URLs/punctuation
  ignored). Suggestion only: both items are kept and `duplicate_of_id` is set.
- **Editing (title, summary, tags, status):** new Edit screen; changed fields are marked `USER` in `field_origins` and
  a version record is written. The AI never overwrites them; unchanged fields stay AI-managed. Original capture is
  untouched. Saved locally (works offline); Notion is updated later only if sync is on.
- **Archive / Delete:** item ⋮ menu. Archive = status ARCHIVED (out of Inbox, still searchable); "Back to Inbox"
  reverses it. Delete asks for confirmation and uses the existing `softDelete`: hidden everywhere, row kept (and in
  backups), Notion page never deleted.

## Tests
| Suite | Result |
|---|---|
| :core:notion | 56/56 |
| :core:data — +8 M3GapsTest (topic/subtopic save + reuse, no topic on low confidence/local, similarity bn/en, near-duplicate flagged & kept, owner edits survive AI, unchanged fields stay AI-managed, archive, soft delete) | 81/81 |
| :app — +2 ItemEditFlowTest (edit 4 fields offline, archive → delete with confirmation) | 46/46 |
| Screenshots | 36/36 |
| Lint | 0 errors |
| assembleDebug / assembleRelease | ✅ |
