#!/usr/bin/env bash
set -euo pipefail
BINTEUM_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BINTEUM_JAVA="${BINTEUM_JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
BINTEUM_SDK="${BINTEUM_ANDROID_SDK:-$HOME/Library/Android/sdk}"
if [[ ! -x "$BINTEUM_JAVA/bin/java" ]]; then
  echo "Set BINTEUM_JAVA_HOME to a JDK 21 installation." >&2
  exit 1
fi
exec env JAVA_HOME="$BINTEUM_JAVA" ANDROID_HOME="$BINTEUM_SDK" \
  PATH="$BINTEUM_JAVA/bin:$BINTEUM_SDK/platform-tools:$PATH" \
  "$BINTEUM_ROOT/gradlew" -p "$BINTEUM_ROOT" "$@"
