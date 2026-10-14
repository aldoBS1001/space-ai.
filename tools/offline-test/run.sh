#!/usr/bin/env bash
# Ejecuta las pruebas del motor central SIN Gradle ni Maven (entornos sin red).
# Compila los MISMOS archivos de src/test con un stub mínimo de org.junit y un runner por reflexión.
# Uso: KOTLINC=/ruta/a/kotlinc/bin/kotlinc tools/offline-test/run.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/tools/offline-test"
KOTLINC="${KOTLINC:-kotlinc}"
OUT="${OUT:-$(mktemp -d)}"
STDLIB="$(dirname "$(dirname "$(command -v "$KOTLINC" || echo "$KOTLINC")")")/lib/kotlin-stdlib.jar"
mkdir -p "$OUT"/{stub,main,test,runner}
javac -d "$OUT/stub" "$HERE"/junitstub/org/junit/*.java
"$KOTLINC" "$ROOT/android/core/src/main/kotlin" -jvm-target 17 -d "$OUT/main"
"$KOTLINC" "$ROOT/android/core/src/test/kotlin" -cp "$OUT/main:$OUT/stub" -Xfriend-paths="$OUT/main" -jvm-target 17 -d "$OUT/test"
javac -cp "$OUT/stub" -d "$OUT/runner" "$HERE/runner/Runner.java"
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT/main:$OUT/test:$OUT/stub:$OUT/runner:$STDLIB" Runner "$OUT/test"
