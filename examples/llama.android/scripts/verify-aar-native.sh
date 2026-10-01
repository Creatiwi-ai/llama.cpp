#!/usr/bin/env bash
#
# Fail when a native library in the AAR cannot load on a 16 KB page device, or when the library
# still carries a log call that writes chat content to logcat.
#
# 1. 16 KB pages. Up to 1.4.2 every .so in the AAR was linked with 4 KB LOAD alignment
#    (p_align 0x1000). Android devices with 16 KB pages refuse to System.loadLibrary() such a
#    library. Every LOAD segment must have p_align >= 0x4000, and GNU_RELRO must end on a 16 KB
#    boundary ((VirtAddr + MemSiz) % 0x4000 == 0), as
#    https://developer.android.com/guide/practices/page-sizes asks.
# 2. Privacy. Up to 1.4.2 ai_chat.cpp logged every formatted chat message (system prompt, user
#    prompt, generated text) at INFO. The format strings of the removed log calls must not appear in
#    any .so or class of the AAR.
# 3. With a source directory, also reject log calls in lib/src/main (C++ and Kotlin) whose
#    arguments name a variable that holds message text. LOGd and LOGv compile out of release
#    builds, so only the source shows them.
#
# Usage: verify-aar-native.sh <lib-release.aar> [lib/src/main]
# Needs unzip, grep, awk and llvm-readelf. llvm-readelf is taken from $READELF, else from the NDK
# under $ANDROID_NDK_HOME, $ANDROID_NDK_ROOT, $ANDROID_HOME/ndk/* or $ANDROID_SDK_ROOT/ndk/*,
# else from PATH.

set -euo pipefail

aar="${1:?usage: $0 <lib-release.aar> [lib/src/main]}"
src="${2:-}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

expected_abis=(arm64-v8a armeabi-v7a x86_64)
expected_libs=(libai-chat.so libggml-base.so libggml-cpu.so libggml.so libllama.so)
min_align=$((0x4000))

# Format strings of the log calls removed in 1.4.3. Each one wrote message text to logcat.
forbidden_strings=(
  'Formatted and added'      # chat_add_and_format(), LOGi: every system, user and assistant message
  'System prompt received'   # processSystemPrompt(), LOGd: the raw system prompt
  'User prompt received'     # processUserPrompt(), LOGd: the raw user prompt
  'token: `%s`'              # LOGv: every prompt token as text
  'cached: `%s`'             # generateNextToken(), LOGv: every generated piece
)

to_unix_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -u "$1"; else printf '%s\n' "$1"; fi
}

find_readelf() {
  if [ -n "${READELF:-}" ]; then
    printf '%s\n' "$READELF"
    return 0
  fi
  local root candidate
  local -a ndks=()
  for root in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do
    [ -n "$root" ] && ndks+=("$(to_unix_path "$root")")
  done
  for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    [ -n "$root" ] || continue
    root="$(to_unix_path "$root")"
    for candidate in "${root%/}"/ndk/*/; do
      [ -d "$candidate" ] && ndks+=("${candidate%/}")
    done
  done
  for root in "${ndks[@]+"${ndks[@]}"}"; do
    for candidate in "$root"/toolchains/llvm/prebuilt/*/bin/llvm-readelf "$root"/toolchains/llvm/prebuilt/*/bin/llvm-readelf.exe; do
      if [ -x "$candidate" ]; then
        printf '%s\n' "$candidate"
        return 0
      fi
    done
  done
  command -v llvm-readelf 2>/dev/null && return 0
  return 1
}

failed=0
fail() {
  echo "FAIL: $*"
  failed=1
}

# grep that tells "no match" (exit 1) apart from an error (exit 2: bad pattern, unreadable file).
# A plain `grep ... || true` would turn an error into a clean pass.
grep_or_fail() {
  local rc=0
  grep "$@" || rc=$?
  if [ "$rc" -gt 1 ]; then
    echo "FAIL: grep exited $rc for: grep $*" >&2
    return 2
  fi
  return 0
}

readelf_bin="$(find_readelf)" || {
  echo "FAIL: llvm-readelf not found. Set READELF, ANDROID_NDK_HOME or ANDROID_HOME."
  exit 1
}
if [ ! -x "$readelf_bin" ] && [ ! -x "$readelf_bin.exe" ]; then
  echo "FAIL: $readelf_bin is not an executable file."
  exit 1
fi
echo "Using $readelf_bin ($("$readelf_bin" --version | grep -m1 -i version))"

unzip -q -o "$aar" -d "$work/aar"

# --- 1. Every expected library is there -------------------------------------------------------
for abi in "${expected_abis[@]}"; do
  for lib in "${expected_libs[@]}"; do
    [ -f "$work/aar/jni/$abi/$lib" ] || fail "jni/$abi/$lib is missing from the AAR."
  done
done

mapfile -t libs < <(cd "$work/aar" && find jni -name '*.so' -type f | LC_ALL=C sort)
if [ "${#libs[@]}" -eq 0 ]; then
  fail "the AAR contains no .so files, so nothing below was checked."
fi

# --- 2. 16 KB alignment -----------------------------------------------------------------------
for so in "${libs[@]}"; do
  headers="$("$readelf_bin" -lW "$work/aar/$so")"
  # Columns: Type Offset VirtAddr PhysAddr FileSiz MemSiz Flg... Align. Flg can be two words
  # ("R E"), so Align is read from the last column.
  aligns="$(awk '$1 == "LOAD" { print $NF }' <<<"$headers")"
  relro="$(awk '$1 == "GNU_RELRO" { print $3, $6 }' <<<"$headers")"

  if [ -z "$aligns" ]; then
    fail "$so has no LOAD segment."
    continue
  fi

  bad_align=""
  while read -r align; do
    if [ $((align)) -lt "$min_align" ]; then
      bad_align="$bad_align $align"
    fi
  done <<<"$aligns"

  relro_note="no GNU_RELRO"
  if [ -n "$relro" ]; then
    read -r relro_vaddr relro_memsz <<<"$relro"
    relro_end=$((relro_vaddr + relro_memsz))
    relro_note="$(printf 'GNU_RELRO ends at 0x%x' "$relro_end")"
    if [ $((relro_end % min_align)) -ne 0 ]; then
      fail "$so: $relro_note, which is not on a 16 KB boundary."
    fi
  fi

  if [ -n "$bad_align" ]; then
    fail "$so has LOAD segments aligned to$bad_align, below 0x4000 (16 KB)."
  else
    echo "ok   $so: LOAD p_align $(tr '\n' ' ' <<<"$aligns")| $relro_note"
  fi
done

# --- 3. No content log formats in the binaries ------------------------------------------------
if [ -f "$work/aar/classes.jar" ]; then
  unzip -q -o "$work/aar/classes.jar" -d "$work/classes"
else
  fail "the AAR has no classes.jar."
fi

for needle in "${forbidden_strings[@]}"; do
  for so in "${libs[@]}"; do
    rc=0
    grep -aqF -- "$needle" "$work/aar/$so" || rc=$?
    case "$rc" in
      0) fail "$so still contains the log format '$needle', which writes message text." ;;
      1) ;;
      *) fail "grep exited $rc reading $so." ;;
    esac
  done
  if [ -d "$work/classes" ]; then
    rc=0
    grep -raqF -- "$needle" "$work/classes" || rc=$?
    case "$rc" in
      0) fail "classes.jar still contains the log format '$needle'." ;;
      1) ;;
      *) fail "grep exited $rc reading the classes of classes.jar." ;;
    esac
  fi
done

# --- 4. Optional: no log call in the sources names a variable holding message text -------------
if [ -n "$src" ]; then
  [ -d "$src/cpp" ] || fail "$src/cpp does not exist."
  [ -d "$src/java" ] || fail "$src/java does not exist."

  # C++: one statement per line, string literals and // comments removed, so only the arguments of
  # a LOGx(...) call are matched, even when the call spans several lines. The rule is strict on
  # purpose: a variable holding message text never appears inside a log call, not even as
  # text.size(). Take the size into its own variable first and log that.
  cpp_content_vars='formatted|formatted_system_prompt|formatted_user_prompt|system_prompt|user_prompt|jsystem_prompt|juser_prompt|content|assistant_text|assistant_ss|cached_token_chars|new_token_chars|new_token_id|common_token_to_piece|chat_msgs\[[^]]*\]'
  cpp_files=0
  while IFS= read -r -d '' file; do
    cpp_files=$((cpp_files + 1))
    [ -r "$file" ] || { fail "$file is not readable."; continue; }
    joined="$(sed -E -e 's/"([^"\\]|\\.)*"/""/g' -e 's://.*$::' "$file" | tr '\n' ' ' | sed 's/;/;\n/g')"
    hits="$(grep_or_fail -E "LOG[vdiwe]\\(.*\\b($cpp_content_vars)\\b" <<<"$joined")" \
      || { fail "the C++ log check could not run on $file."; continue; }
    if [ -n "$hits" ]; then
      fail "$file has a log call whose arguments include message text:"
      printf '       %s\n' "$hits"
    fi
  done < <(find "$src/cpp" -type f \( -name '*.cpp' -o -name '*.h' \) -print0)
  [ "$cpp_files" -gt 0 ] || fail "no C++ sources under $src/cpp, so the C++ log check saw nothing."

  # Kotlin: Log.x(...) with a template or an argument naming the prompt, the message or a token.
  kt_content_vars='message|prompt|systemPrompt|userPrompt|utf8token|token'
  kt_files="$(find "$src/java" -type f -name '*.kt' | wc -l)"
  [ "$kt_files" -gt 0 ] || fail "no Kotlin sources under $src/java, so the Kotlin log check saw nothing."
  if kt_hits="$(grep_or_fail -rnE --include='*.kt' "Log\\.[vdiwe]\\(.*(\\\$\\{?($kt_content_vars)\\b|, *($kt_content_vars) *[,)])" "$src/java")"; then
    if [ -n "$kt_hits" ]; then
      fail "Kotlin log calls include message text:"
      printf '       %s\n' "$kt_hits"
    fi
  else
    fail "the Kotlin log check could not run."
  fi
fi

if [ "$failed" -ne 0 ]; then
  exit 1
fi

echo "OK: ${#libs[@]} native libraries, all LOAD segments aligned to >= 16 KB and GNU_RELRO ends on"
echo "    16 KB; no log format that writes message text in any .so or class${src:+, nor in $src}."
