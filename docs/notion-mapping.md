# Notion mapping — My knowledge Hub → Master Database

Approved by the owner on 2026-11-20. Any change to this file that writes new properties
or options needs a new approval.

## Target

| Item | Value |
|---|---|
| Database | 📊 Master Database (`3d290cde650c80cc9307fe589aacd3bf`) |
| Data source | `3d290cde-650c-8001-9cab-000bf4ce921c` |
| API version | `2026-03-11` (data-source endpoints) |

## Schema change log

| Date | Change | Approved | Verified |
|---|---|---|---|
| 2026-11-20 | Added `Capture ID` (rich text). Nothing else changed. | Yes | Other 14 properties identical; 15 rows unchanged; 0 rows have a Capture ID |

## Owner rules (non-negotiable)

1. No property is renamed, deleted or changed. Only `Capture ID` was added.
2. No new select / multi-select option is created. Values without a matching option are skipped and listed as proposals.
3. The 15 pre-existing entries are never modified. PKOS only touches pages whose `Capture ID` equals the item's UUID.
4. The live schema is read before every write.
5. Sync failure never changes local data.
6. One Capture ID → at most one page.

## Property mapping

| PKOS field | Notion property | Rule |
|---|---|---|
| title | `Topic name` (title) | ≤2000 chars |
| capture_id | `Capture ID` (rich text) | lowercase UUID; idempotency key |
| source_url | `Link/URL` (url) | tracking params removed; >2000 chars → page body |
| created_at | `Date Added` (date) | date only, Asia/Dhaka |
| content_type | `Type` | video→Video · article/website/tool/tutorial→Website · note/message→Note · idea→Idea · document/image→File · other→skip |
| status | `Status` | inbox/to_review→Inbox · to_learn→To Learn · in_progress→In Progress · done→Completed · archived→skip |
| priority | `Priority` | low/normal→Normal · high/critical→High |
| pinned | `Pinned` | — |
| main_category | `category ` (trailing space) | exact or alias match only; else try Tags; else skip |
| tags | `Tags` | existing options only |
| source_platform | `platform ` (trailing space) | Youtube / Facebook only |
| attachments | `Files & media` | Notion file_upload id or external link |

Never written: `Notes` (it is a select), `Parent item`, `Sub-item`.

## Page body (content blocks, no new properties)

Summary · Key points · Topic / Subtopic / Purpose / AI Confidence / review reason ·
Original text · OCR text · Source app · full URL if too long · footer.

**Body marker.** The footer is always the last PKOS block and contains
`pkos-body:v1:<capture-id>`. PKOS treats its body as present only if that exact token
(for this item) is found while paging through the page's blocks. The owner's own blocks,
or a footer copied from another item, do not count.

- Written once: at create, or appended once when AI finishes later. Never rewritten.
- Append only adds at the end. No block is ever edited or deleted (the client has no such call).
- If the page is too long to scan (default 20 × 100 blocks) the state is `UNKNOWN` and nothing is appended.
- If the owner deletes the footer *and* the local mapping is lost, PKOS would append its body again.

## Conflict and adoption policy

After every verified write PKOS stores a SHA-256 of the managed property values read back
from Notion. Before every write it stores a **write intent** (kind + hash of what it is
about to send) in the local journal.

| Situation | Remote values equal… | Result |
|---|---|---|
| Linked page | what PKOS would write now | nothing written |
| Linked page | PKOS's last verified write or last intent | update |
| Linked page | something else | `Conflict(REMOTE_EDITED)` — nothing written |
| No local mapping, page found by Capture ID | what PKOS would write now | adopt, nothing written |
| No local mapping, page found by Capture ID | PKOS's last intent (journal survived) | adopt + update |
| No local mapping, page found by Capture ID | something else | `Conflict(ADOPTION_REVIEW)` — nothing written |

The owner resolves a conflict with **Overwrite Notion** (write local values) or **Keep Notion**
(write nothing; the app must copy `keptRemoteValues` into the local item before the next sync).

## Crash and lost-response safety

| Failure | Recovery |
|---|---|
| Create applied, response lost | Journal has CREATE intent → query by Capture ID finds the page → adopted, no second page |
| …and the query index lags | Wait (`AWAITING_REMOTE_INDEX`, default 120 s) instead of creating again |
| Create never reached Notion | After the grace period, create once |
| Read-back after create failed | Page id was journalled → fetched directly, even if the index lags |
| Local mapping save failed | Next sync adopts via Capture ID; journal hash prevents a false conflict |
| Property update applied, response lost | Remote equals intent → no false conflict, no resend |
| Body append applied, response lost | Marker scan finds it → not appended again |
| Journal cannot be read or written | Nothing is sent; retry later |

Every request of a create and of an update+append was tested with both failure modes
(`CrashChaosTest`).

## Error handling

| Notion response | PKOS state |
|---|---|
| 401 | Action required — reconnect token |
| 403 | Action required — share the database with the integration |
| 404 (data source) | Action required — database not shared / moved |
| 404 (linked page) | Action required — page deleted; not recreated silently |
| 400 | Action required — validation (likely schema changed) |
| 409, 429, 5xx, network | Retry with backoff (honours Retry-After), max 8 attempts |
