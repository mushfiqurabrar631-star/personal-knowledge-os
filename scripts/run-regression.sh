#!/usr/bin/env bash
# Full automated regression for Final Testing case AUTO-01/02 (and the unsigned part of AUTO-04).
# Runs: clean → all JVM/Robolectric tests → screenshots → lint → debug/release/r8check/androidTest APKs → AAB.
# Results: build/final-testing/summary.txt (+ copied JUnit XML). Read-only for user data: it touches no phone,
# no Notion, no Drive and no account. Screenshots under docs/screenshots are restored afterwards (evidence stays
# the reviewed version).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/final-testing; rm -rf "$OUT"; mkdir -p "$OUT/xml"
G="./gradlew --no-daemon --max-workers=${PKOS_GRADLE_WORKERS:-2}"
step() { local name=$1; shift; "$@" > "$OUT/$name.log" 2>&1; local rc=$?; echo "$name=$rc" | tee -a "$OUT/summary.txt"; return 0; }
echo "commit=$(git rev-parse --short HEAD) date=$(date -Iseconds)" > "$OUT/summary.txt"
step clean $G clean
step tests $G :core:notion:test :core:data:testDebugUnitTest :app:testDebugUnitTest
for d in core/notion/build/test-results/test core/data/build/test-results/testDebugUnitTest app/build/test-results/testDebugUnitTest; do
  [ -d "$d" ] && cp "$d"/*.xml "$OUT/xml/" 2>/dev/null
done
step screenshots $G :app:recordRoborazziDebug
git checkout -- docs/screenshots 2>/dev/null
step lint $G :app:lintDebug :core:data:lintDebug
step assemble $G :app:assembleDebug :app:assembleRelease :app:assembleR8check :app:assembleDebugAndroidTest
step bundle $G :app:bundleRelease
python3 - "$OUT" <<'EOF' | tee -a "$OUT/summary.txt"
import glob, re, sys
t = f = 0
for p in glob.glob(sys.argv[1] + "/xml/*.xml"):
    m = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', open(p, encoding="utf-8").read())
    if m: t += int(m[1]); f += int(m[3]) + int(m[4])
print(f"junit_tests={t} junit_failures={f}")
for r in ("app/build/reports/lint-results-debug.xml", "core/data/build/reports/lint-results-debug.xml"):
    try: print(f"lint_errors[{r.split('/')[0]}]={len(re.findall(r'severity=\"(?:Error|Fatal)\"', open(r).read()))}")
    except OSError: print(f"lint_errors[{r}]=missing")
EOF
ls -l app/build/outputs/apk/*/*.apk app/build/outputs/bundle/release/*.aab 2>/dev/null | awk '{print $5, $9}' >> "$OUT/summary.txt"
sha256sum app/build/outputs/apk/release/*.apk >> "$OUT/summary.txt" 2>/dev/null
echo "--- $OUT/summary.txt"; cat "$OUT/summary.txt"
grep -qE "^(tests|lint|assemble|bundle)=[1-9]" "$OUT/summary.txt" && exit 1 || exit 0
