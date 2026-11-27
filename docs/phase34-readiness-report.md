# Phase 3/4 readiness report — Final Testing preparation (2026-11-27)

**Status: preparation complete; Final Testing NOT started. 96/96 cases are `NOT_RUN`.** PKOS 1.0.0 is a release
candidate and will not be declared released until `python3 scripts/testplan.py gate` passes (all 96 PASS, or SKIPPED
with a reason, each with evidence). No phone test was started (rule 6), no account was used, no user data, Notion
page, database, backup or setting was touched, and nothing was force-pushed.

## 1. Review of Phase 2 (findings and fixes)
| Finding | Action |
|---|---|
| AUTO-01 referred to `/tmp/final.sh`, which is not in the repository (not reproducible by the owner) | Added `scripts/run-regression.sh` (same steps + AAB, writes `build/final-testing/summary.txt`) |
| setup-guide: link previews said "Settings → Capture"; it is under **Knowledge tools** | Corrected |
| setup/user guide: floating button said "Settings → Permissions"; the switch is **Settings → Capture → Floating save button** | Corrected (Permissions only shows the status) |
| SEC-05 had no tool | Added `scripts/secret-scan.sh` (tracked key files + secret patterns; prints only file:line). Verified it catches planted fake secrets and has no false positives on HEAD; added to CI |
| Tests needed realistic inputs | Added `scripts/make-test-fixtures.py` + `testing/fixtures/` (Bengali screenshot and 12-page scanned PDF rendered with the app's Hind Siliguri font and correct shaping, English photo, text PDF, 7.9 MB PDF, multi-share images, fake sensitive and TODO samples, SHA256SUMS) |
No app code, build configuration, signing or OAuth setting was changed in this phase.

## 2. The 96 cases — now complete procedures
Each case in `testing/final-testing-results.csv` (rendered as `docs/final-testing-plan.md`) now has: where it runs,
which account it needs, its risk to data, prerequisites (coded, e.g. PR-SAFE), numbered steps, a measurable expected
result and the evidence file name (`testing/evidence/<ID>-*`).

| Split | Cases |
|---|---|
| CI / build machine (no phone, no account) | 5 — AUTO-01, AUTO-02, AUTO-05, AUTO-06, SEC-05 |
| Owner's computer | 4 — AUTO-03, AUTO-04 (signed build), BAK-03 (needs Drive), EXP-02 (inspect downloaded files) |
| Real phone, no account | 53 — DEV, CAP, OCR, OFF, SRCH, AI-01, EXP-01/03/04, SEC-03/04, A11Y, PERF, DATA-01/02/04/05, FEAT, FLOW-03/04 |
| Real phone + Google Drive | 16 — OAUTH ×5, BAK ×8 (BAK-03 is on the computer), EXP-05, SEC-01, DATA-03 |
| Real phone + Notion | 9 — NOT-01…08, SEC-02 |
| Real phone + Notion + Drive | 4 — NOT-09, NOT-10, FLOW-01, FLOW-02 |
| Real phone + own Gemini key | 4 — AI-02, AI-03, AI-04, AI-06 |
| Real phone + own OpenAI-compatible endpoint | 1 — AI-05 (SKIPPED with reason if you have none; never buy one) |

Risk: 77 SAFE · 14 CHANGES_TEST_DATA (PKOS-TEST pages in Notion, files in Drive) · 5 DESTRUCTIVE
(BAK-06, EXP-05, SEC-04, DATA-04, FLOW-02). Destructive cases run last, only after a fresh verified backup + export
(PR-SAFE) and your explicit OK at that moment; the record tool refuses a result for them without `owner-approved`.

## 3. Pass/Fail recording safeguards (verified)
`scripts/testplan.py` refuses: PASS without date, build, device and evidence; FAIL/BLOCKED/SKIPPED without notes;
a destructive result without `owner-approved`; unknown values; empty procedure fields. `gate` fails while any case is
not PASS. Tested on a temporary copy (then restored): all three bad records were refused. CI validates the CSV on every push.

## 4. Owner prerequisites (no secrets requested)
Step-by-step in [owner-prerequisites.md](owner-prerequisites.md):
1. Create the release keystore outside the repo and store it + its password in two safe places.
2. Build the signed release (`scripts/build-signed-release.sh`, or the PowerShell commands) — gives AUTO-03/04.
3. Add the release Android OAuth client: package `com.pkos.app` + release SHA-1 (existing settings unchanged).
4. Gradle checksum: copy from gradle.org, confirm with a second source (download + sha256), add `distributionSha256Sum` — or send me the public value.
5. Phone prep: verified backup + export of existing data first; copy `testing/fixtures/` to the phone.
6. Notion: baseline screenshots/CSV of the database schema and the 15 legacy entries; approve the PKOS-TEST pages.

## 5. Readiness checklist
| Item | State |
|---|---|
| Development M1–M8 | done (Phase 1 + 2 reports) |
| Automated regression in repo | done — run result below |
| CI (tests, lint, builds, emulator, R8 smoke, record + secret checks) | in place |
| 96 detailed procedures + split + evidence rules | done |
| Test fixtures | done |
| Signed release APK | **waiting for owner (step 1–2)** |
| Release OAuth client | **waiting for owner (step 3)** |
| Gradle checksum | **waiting for owner (step 4)** |
| Phone + data safety prep, Notion baseline/approval | **waiting for owner (steps 5–6)** |
| Final Testing | not started — starts after the four waiting items |

## 6. Regression run for this phase (`scripts/run-regression.sh`, commit c99ea2e + this phase's uncommitted docs/scripts)
clean=0 · tests=0 · screenshots=0 · lint=0 · assemble=0 · bundle=0 — **294 JUnit tests, 0 failures**
(`:core:notion` 56, `:core:data` 122, `:app` 68, screenshots 48); lint errors 0 (app, core:data).
Outputs: debug arm64 57.7 MB / universal 107.0 MB; release (unsigned) arm64 34.3 MB / universal 83.6 MB; AAB 50.6 MB.
Release APK hashes differ from the Phase 2 run because the build embeds the Git commit
(`META-INF/version-control-info.textproto`); same commit → same bytes (see reproducible-build.md).
These are development results only; AUTO-01…06 will be run again on the final release candidate.
