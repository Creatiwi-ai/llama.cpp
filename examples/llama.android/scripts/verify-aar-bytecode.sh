#!/usr/bin/env bash
#
# Fail when the compiled InferenceEngineImpl cannot generate tokens, or still marks JNI calls @FastNative.
#
# llama-android 1.1.0 to 1.4.1 were compiled with Kotlin 2.0.0, which turned the generation loop of
# InferenceEngineImpl.sendUserPrompt into `throw KotlinNothingValueException()`: every successful
# prefill failed, and the AAR never produced a token. Nothing in the build notices that, so this
# script reads the bytecode of the flow lambda instead.
#
# Usage: verify-aar-bytecode.sh <lib-release.aar | classes.jar | classes directory>
# Needs javap (any JDK) and unzip on PATH.

set -euo pipefail

input="${1:?usage: $0 <lib-release.aar | classes.jar | classes directory>}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

case "$input" in
  *.aar)
    unzip -q -o "$input" classes.jar -d "$work"
    classpath="$work/classes.jar"
    ;;
  *)
    classpath="$input"
    ;;
esac

engine='com.arm.aichat.internal.InferenceEngineImpl'
lambda="$engine\$sendUserPrompt\$1"

javap -c -p -classpath "$classpath" "$lambda" > "$work/lambda.txt"
javap -v -p -classpath "$classpath" "$engine" > "$work/engine.txt"

failed=0

if ! grep -qE 'Method com/arm/aichat/internal/InferenceEngineImpl\.(access\$)?generateNextToken:' "$work/lambda.txt"; then
  echo "FAIL: $lambda never calls generateNextToken, so the generation loop is missing."
  failed=1
fi

if ! grep -qF 'InterfaceMethod kotlinx/coroutines/flow/FlowCollector.emit:' "$work/lambda.txt"; then
  echo "FAIL: $lambda never calls FlowCollector.emit, so no token reaches the collector."
  failed=1
fi

if grep -qF 'kotlin/KotlinNothingValueException' "$work/lambda.txt"; then
  echo "FAIL: $lambda throws KotlinNothingValueException (the Kotlin 2.0.0 dead-code bug)."
  failed=1
fi

if grep -qF 'Ldalvik/annotation/optimization/FastNative;' "$work/engine.txt"; then
  echo "FAIL: $engine marks JNI methods @FastNative. They run for seconds and stall garbage collection."
  failed=1
fi

if [ "$failed" -ne 0 ]; then
  echo "--- javap -c $lambda ---"
  cat "$work/lambda.txt"
  exit 1
fi

echo "OK: $lambda calls generateNextToken and FlowCollector.emit, has no KotlinNothingValueException,"
echo "    and $engine has no @FastNative methods."
