# Phase 2 report — M8 release development (2026-11-27)

**Status: M8 release development is done; M8 Final Testing has not started** (no phone or acceptance testing was
done, as instructed). Google OAuth settings and the existing debug signing were not changed; no paid service is used;
no key or password is in the repository.

## Done
| # | Task | Result | Evidence |
|---|---|---|---|
| 1 | Release signing | `pkosRelease` config from `PKOS_RELEASE_*` env vars or git-ignored `local.properties`; v2 + v3 signatures; unsigned when absent. `scripts/build-signed-release.sh` (refuses a keystore inside the repo) | Signed with a throwaway key outside the repo (then deleted): `apksigner verify` v2 ✓ v3 ✓, `com.pkos.app` 1.0.0 / 10000 |
| 2 | R8 + resource shrinking | `isMinifyEnabled` + `isShrinkResources`; keep rules for Tesseract JNI, pdfbox resources, BouncyCastle, stored enum names, workers | Release builds; mapping checked (TessBaseAPI, workers, `PkosDatabase_Impl` kept). CI emulator: shrunk `r8check` build launched + 300 monkey events, no crash |
| 3 | ABI splits + AAB | arm64-v8a / armeabi-v7a / x86_64 / x86 + universal; `bundleRelease` (separate Gradle run; APK splits auto-off for bundle tasks) | arm64 release 34.3 MB (was 102.5 MB universal unshrunk); arm64 debug 57.7 MB (was 111 MB); AAB 50.6 MB |
| 4 | Release OAuth / package / SHA-1 docs | `docs/release-signing-and-oauth.md` | — |
| 5 | CI instrumentation | `androidTest/DeviceSmokeTest` (real Keystore, SQLite search, Tesseract native + Bengali model, app launch); CI emulator job (API 34) + R8 smoke; test-count check added; testplan record check | CI run 46032352351: 4/4 device tests, R8 smoke ✓ |
| 6 | Documentation | README, installation, setup, A-to-Z user guide, OAuth, recovery runbook, reproducible build | `docs/` |
| 7 | Version + changelog | 1.0.0 / 10000, `CHANGELOG.md` | — |
| 8 | Final Testing Plan + Pass/Fail system | 96 cases in 17 areas (`testing/final-testing-results.csv`, all NOT_RUN), `scripts/testplan.py check/gate/render/set` refuses PASS without date, build, device and evidence | `docs/final-testing-plan.md` |
| 9 | Limits + security in release docs | `docs/security-and-limitations.md`, CHANGELOG, README | — |

## Automated results (clean build on the build machine)
`:core:notion` 56/56 · `:core:data` 122/122 · `:app` 68/68 · screenshots 48/48 · lint 0 errors (app, core:data) ·
assembleDebug, assembleRelease, assembleR8check, assembleDebugAndroidTest, bundleRelease all OK.
Reproducibility: two clean `assembleRelease` runs gave identical SHA-256 for all 5 unsigned release APKs.
The androidTest suite cannot run on the build machine (no emulator); it runs on the CI emulator (API 34 x86_64).
CI run 46032352351 (commit 94dc5fe): build ✓; instrumentation ✓ — "instrumentation test cases run: 4" (all passed),
R8 `r8check` build launched, 300 monkey events, no crash. (An earlier run hung on a count command that read stdin;
it was cancelled and fixed.)

## Not done / remaining (before 1.0.0 can be called released)
- Owner: create the release keystore on your computer, build with `scripts/build-signed-release.sh`, add the release
  Android OAuth client (`com.pkos.app` + release SHA-1).
- Owner: pin the Gradle wrapper checksum (`distributionSha256Sum`) from gradle.org (not reachable from the sandbox).
- Phase 3/4: Final Testing (96 cases), including every real-phone, Drive, Notion, AI, TalkBack, performance and R8
  release-runtime check. Nothing there is marked passed.
- Reproducibility across different machines is not checked yet.
