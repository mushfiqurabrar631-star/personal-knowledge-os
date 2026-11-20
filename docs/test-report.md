# Test report — core:notion review fixes (2026-11-20)

Command: `./scripts/verify-core-notion.sh --report docs/test-run-2026-11-20.txt`
Toolchain: Kotlin 2.0.21 (compiler found automatically), OpenJDK 21.
Result: **50 tests, 50 passed, 0 failed.** Full per-test output: [test-run-2026-11-20.txt](test-run-2026-11-20.txt).

Not covered here: real Notion API calls (no token yet), Android build (Gradle repositories blocked).
Notion itself was not modified in this round; read-only check: schema identical, 15 rows, 0 with Capture ID.

## New tests (21)

### Requirement 1 + 4 — body marker, no duplicate body, owner content intact (`BodyMarkerTest`)
| Test | Purpose | Result |
|---|---|---|
| builtBodyEndsWithThisItemsMarker | Even a huge body (OCR 60k chars) stays ≤100 blocks and its last block carries this item's marker, exactly once | PASS |
| ownerBlocksWithoutMarkerDoNotCountAsPkosBody | Page with only the owner's notes → PKOS body is still appended once; owner blocks stay first and byte-identical | PASS |
| markerOfAnotherItemIsIgnored | A footer copied from a different Capture ID does not count as this item's body | PASS |
| lostAppendResponseDoesNotDuplicateBody | Append applied but response lost; local still says "not written" → scan finds marker, no second append | PASS |
| markerOnLaterChildrenPageIsFound | Marker on page 4 of block listing (page size 2) is found through pagination | PASS |
| tooLongToScanIsUnknownAndNothingIsAppended | Page longer than the scan limit → state UNKNOWN, nothing appended | PASS |
| ownerContentSurvivesAFullLifecycle | 4 syncs (create, enrich, retitle, pin) around owner notes → owner blocks unchanged, one body, body never rewritten, no delete or block edit calls | PASS |

### Requirement 2 — lost local mapping, adoption review (`AdoptionAndRecoveryTest`)
| Test | Purpose | Result |
|---|---|---|
| adoptionOfUnchangedPageWritesNothing | Journal + mapping lost, remote equals local → adopted with zero writes | PASS |
| adoptionOfRemotelyEditedPageNeedsReviewAndWritesNothing | Owner changed Status in Notion, local data lost → ADOPTION_REVIEW with diff (Status: Completed vs To Learn), no write; Keep → no write + values to pull; Overwrite → written | PASS |
| adoptionWithLocalChangesButNoJournalIsReviewedNotGuessed | No journal and values differ → review, never a silent overwrite | PASS |
| journalProvesOwnWriteSoLocalChangeIsNotAFalseConflict | Mapping save failed, item edited locally, journal intact → recognised as own write, updated, no false conflict | PASS |

### Requirement 3 — crash / failure at each step (`AdoptionAndRecoveryTest`, `CrashChaosTest`)
| Test | Purpose | Result |
|---|---|---|
| readBackFailureAfterCreateRecoversWithoutDuplicate | Create OK, read-back 500 → retry adopts the same page; 1 create, 1 page, 1 body | PASS |
| readBackFailureWhileQueryIndexLagsUsesKnownPageId | Same, but query can't see the new page yet → journalled page id fetched directly | PASS |
| lostCreateResponseWithIndexLagWaitsInsteadOfDuplicating | Create applied, response lost, index lagging → AWAITING_REMOTE_INDEX instead of a 2nd create; adopted once visible | PASS |
| createThatNeverReachedNotionIsRetriedAfterGracePeriod | Create failed before Notion → waits the grace period, then creates exactly one page | PASS |
| lostPatchResponseIsNotAFalseConflict | Property update applied, response lost, link has old hash → no conflict, no resend | PASS |
| journalFailureStopsBeforeAnyWrite | Journal write or read fails → Retry(LOCAL_JOURNAL), zero Notion writes | PASS |
| journalRecordsIntentBeforeEachWrite | Intents recorded in order CREATE(no id) → CREATE(id) → UPDATE → APPEND_BODY | PASS |
| everyStepOfCreateIsSafeToRetry | Failure injected at each of the 4 requests of a create, in both modes (fails before applying / applied but response lost) = 8 runs: always 1 page, 1 body, no conflict; re-sync without mapping writes nothing | PASS |
| everyStepOfUpdateAndBodyAppendIsSafeToRetry | Same for the 6 requests of update + body append = 12 runs: 1 page, body present exactly once, title updated, legacy untouched | PASS |

### Other
| Test | Purpose | Result |
|---|---|---|
| NotionSyncEngineTest.identicalResyncWritesNothing | Re-syncing an unchanged item sends no write | PASS |

All engine tests also check, via `TestEnv.assertLegacyUntouched()`, that the 15 legacy rows' properties and blocks
are byte-identical, no new select option was attempted, and no DELETE request was made.

## Changed existing tests
- `remoteEditIsReportedAsConflictAndNotOverwritten` now also checks conflict kind, the differing properties and values.
- `retryableErrorsScheduleBackoff` uses the new `RetryReason`.
- `duplicateRemotePagesNeedUserDecision` clears the journal first (otherwise the known page id is used directly).
- `lostLocalLinkIsRecoveredByCaptureIdLookup` and `responseLostAfterCreateDoesNotDuplicateOnRetry` were replaced by the
  stricter adoption / recovery tests above.

## Mutation check (do the tests catch real bugs?)
Each protection was deliberately broken, the suite re-run, then the code restored (50/50 again).

| Broken on purpose | Tests that failed |
|---|---|
| Any block counts as PKOS body | 5 (BodyMarkerTest) |
| Adoption overwrites without review | 2 |
| Journal not used to recognise own writes | 1 |
| No index-lag grace period | 2 |
| No direct lookup by journalled page id | 1 |
| Option guard removed | 4 |

## Script portability (requirement 5)
| Check | Result |
|---|---|
| No hard-coded `/opt/gradle-8.14.3`; searches `KOTLIN_JARS_DIR`, `GRADLE_HOME`, `gradle` on PATH, wrapper dists, `/opt`, `/usr/share`, `/usr/local`, `~/.m2`, `~/.gradle/caches` | OK |
| Jars copied to `/tmp/jars`, `KOTLIN_JARS_DIR=/tmp/jars` → that compiler used, 50/50 pass | OK |
| A jar missing (`KOTLIN_COMPILER_JAR=/nope.jar`) → lists missing jars + 3 fixes, exit 1 | OK |
| No Java on PATH → "Java 17+ is required", exit 1 | OK |
| `--gradle` delegates to `gradle :core:notion:test` (needs internet) | not run here |

## Bug found while testing
`TestReport` dropped the first test class when `--report` was not given (39 instead of 50 tests). Fixed; both modes now run 50.
