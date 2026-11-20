#!/usr/bin/env bash
# Compiles and tests :core:notion without network access.
#
# Order of preference:
#   1. --gradle            → runs `gradle :core:notion:test` (needs Maven Central / Gradle Plugin Portal).
#   2. Local jars          → finds a Kotlin compiler + libraries automatically:
#        $KOTLIN_JARS_DIR, $GRADLE_HOME/lib, the lib/ dir of `gradle` on PATH, ~/.gradle/wrapper/dists/*/*/gradle-*/lib,
#        /opt/gradle*/lib, /usr/share/gradle*/lib, /usr/local/gradle*/lib, ~/.m2/repository, ~/.gradle/caches/modules-2.
#      Override any single jar with an env var (see `need` below).
#
# Required (any recent compatible versions):
#   kotlin-compiler-embeddable, kotlin-stdlib, kotlin-script-runtime, kotlin-reflect, kotlin-daemon-embeddable,
#   trove4j (compiler runtime), annotations (org.jetbrains), kotlinx-coroutines-core-jvm,
#   kotlinx-serialization-core-jvm, kotlinx-serialization-json-jvm, junit 4.x, hamcrest-core 1.3, and Java 17+.
#
# Options: --gradle | --report <file>
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$ROOT/core/notion"
REPORT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --gradle) cd "$ROOT"; exec gradle :core:notion:test ;;
    --report) REPORT="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done

command -v java >/dev/null || { echo "ERROR: Java 17+ is required (java not on PATH)." >&2; exit 1; }

# ---- candidate directories -------------------------------------------------------------------------------
DIRS=()
[[ -n "${KOTLIN_JARS_DIR:-}" ]] && DIRS+=("$KOTLIN_JARS_DIR")
[[ -n "${GRADLE_HOME:-}" ]] && DIRS+=("$GRADLE_HOME/lib")
if command -v gradle >/dev/null; then
  G="$(readlink -f "$(command -v gradle)")"; DIRS+=("$(dirname "$(dirname "$G")")/lib")
fi
shopt -s nullglob
for d in "$HOME"/.gradle/wrapper/dists/*/*/gradle-*/lib /opt/gradle*/lib /usr/share/gradle*/lib /usr/local/gradle*/lib; do DIRS+=("$d"); done
for d in "$HOME/.m2/repository" "$HOME/.gradle/caches/modules-2"; do [[ -d "$d" ]] && DIRS+=("$d"); done

# find_jar <glob-name>  → newest matching jar (excluding sources/javadoc) in any candidate dir
find_jar() {
  local pattern="$1" hit=""
  for d in "${DIRS[@]}"; do
    [[ -d "$d" ]] || continue
    hit="$(find "$d" -maxdepth 8 -name "$pattern" ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | sort -V | tail -1)"
    [[ -n "$hit" ]] && { echo "$hit"; return 0; }
  done
  return 1
}

MISSING=()
# need <ENV_VAR> <glob>   → sets ENV_VAR to an existing path (env override wins)
need() {
  local var="$1" pattern="$2" val="${!1:-}"
  if [[ -z "$val" ]]; then val="$(find_jar "$pattern" || true)"; fi
  if [[ -z "$val" || ! -f "$val" ]]; then MISSING+=("$var ($pattern)"); val=""; fi
  printf -v "$var" '%s' "$val"
}

need KOTLIN_COMPILER_JAR 'kotlin-compiler-embeddable-*.jar'
KV=""; [[ -n "$KOTLIN_COMPILER_JAR" ]] && KV="$(basename "$KOTLIN_COMPILER_JAR" | sed -E 's/kotlin-compiler-embeddable-(.*)\.jar/\1/')"
need KOTLIN_STDLIB_JAR "kotlin-stdlib-${KV:-*}.jar"
need KOTLIN_SCRIPT_RUNTIME_JAR "kotlin-script-runtime-${KV:-*}.jar"
need KOTLIN_REFLECT_JAR "kotlin-reflect-${KV:-*}.jar"
need KOTLIN_DAEMON_JAR "kotlin-daemon-embeddable-${KV:-*}.jar"
need TROVE4J_JAR 'trove4j-*.jar'
need ANNOTATIONS_JAR 'annotations-[0-9]*.jar'
need COROUTINES_JAR 'kotlinx-coroutines-core-jvm-*.jar'
need SERIALIZATION_CORE_JAR 'kotlinx-serialization-core-jvm-*.jar'
need SERIALIZATION_JSON_JAR 'kotlinx-serialization-json-jvm-*.jar'
need JUNIT_JAR 'junit-4.*.jar'
need HAMCREST_JAR 'hamcrest-core-*.jar'

if [[ ${#MISSING[@]} -gt 0 ]]; then
  echo "ERROR: could not find these jars:" >&2
  printf '  - %s\n' "${MISSING[@]}" >&2
  cat >&2 <<'MSG'

Fix one of:
  a) Install Gradle 8.x locally (its lib/ folder contains the Kotlin compiler, stdlib, coroutines,
     serialization, junit and hamcrest), then re-run this script.
  b) Point KOTLIN_JARS_DIR at a folder that contains the jars above, or set the listed env vars to jar paths.
  c) With internet access to Maven Central: ./scripts/verify-core-notion.sh --gradle
MSG
  exit 1
fi

OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
KC="$KOTLIN_COMPILER_JAR:$KOTLIN_STDLIB_JAR:$KOTLIN_SCRIPT_RUNTIME_JAR:$KOTLIN_REFLECT_JAR:$KOTLIN_DAEMON_JAR:$COROUTINES_JAR:$TROVE4J_JAR:$ANNOTATIONS_JAR"
LIBS="$KOTLIN_STDLIB_JAR:$SERIALIZATION_JSON_JAR:$SERIALIZATION_CORE_JAR:$COROUTINES_JAR"
TESTLIBS="$JUNIT_JAR:$HAMCREST_JAR"
kotlinc() { java -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 "$@"; }

echo "== toolchain: Kotlin ${KV:-?} · $(java -version 2>&1 | grep -v "Picked up" | head -1)"
echo "== compiler jar: $KOTLIN_COMPILER_JAR"
echo "== compile main"
kotlinc -cp "$LIBS" $(find "$MOD/src/main/kotlin" -name '*.kt') -d "$OUT/main"
echo "== compile test"
kotlinc -cp "$LIBS:$TESTLIBS:$OUT/main" $(find "$MOD/src/test/kotlin" -name '*.kt') -d "$OUT/test"
echo "== run tests"
CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' ! -name '*$*' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
ARGS=()
[[ -n "$REPORT" ]] && ARGS+=(--out "$REPORT")
java -cp "$LIBS:$TESTLIBS:$OUT/main:$OUT/test" com.pkos.core.notion.TestReport "${ARGS[@]}" $CLASSES
