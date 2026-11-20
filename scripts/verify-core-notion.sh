#!/usr/bin/env bash
# Compiles and tests :core:notion with the Kotlin compiler bundled in a local Gradle install.
# Used where Maven/Google repositories are unreachable. In CI, use ./gradlew :core:notion:test instead.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GL="${GRADLE_LIB:-/opt/gradle-8.14.3/lib}"
MOD="$ROOT/core/notion"
OUT="$(mktemp -d)"
KC="$GL/kotlin-compiler-embeddable-2.0.21.jar:$GL/kotlin-stdlib-2.0.21.jar:$GL/kotlin-script-runtime-2.0.21.jar:$GL/kotlin-reflect-2.0.21.jar:$GL/kotlin-daemon-embeddable-2.0.21.jar:$GL/kotlinx-coroutines-core-jvm-1.6.4.jar:$GL/trove4j-1.0.20200330.jar:$(ls "$GL"/annotations-*.jar | head -1)"
LIBS="$GL/kotlin-stdlib-2.0.21.jar:$GL/kotlinx-serialization-json-jvm-1.6.2.jar:$GL/kotlinx-serialization-core-jvm-1.6.2.jar:$GL/kotlinx-coroutines-core-jvm-1.6.4.jar"
TESTLIBS="$GL/junit-4.13.2.jar:$GL/hamcrest-core-1.3.jar"
kotlinc() { java -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -Werror=false "$@"; }
echo "== compile main"
kotlinc -cp "$LIBS" $(find "$MOD/src/main/kotlin" -name '*.kt') -d "$OUT/main"
echo "== compile test"
kotlinc -cp "$LIBS:$TESTLIBS:$OUT/main" $(find "$MOD/src/test/kotlin" -name '*.kt') -d "$OUT/test"
echo "== run tests"
CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' ! -name '*$*' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
java -cp "$LIBS:$TESTLIBS:$OUT/main:$OUT/test" org.junit.runner.JUnitCore $CLASSES
