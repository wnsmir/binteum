#!/usr/bin/env bash
set -euo pipefail
BINTEUM_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BINTEUM_SDK="${BINTEUM_ANDROID_SDK:-$HOME/Library/Android/sdk}"
BINTEUM_JAVA="${BINTEUM_JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
echo "Project: $BINTEUM_ROOT"
echo "Android SDK: $BINTEUM_SDK"
"$BINTEUM_JAVA/bin/java" -version
for BINTEUM_ITEM in platforms/android-34/android.jar build-tools/36.1.0/aapt2 platform-tools/adb; do
  if [[ -f "$BINTEUM_SDK/$BINTEUM_ITEM" ]]; then echo "OK $BINTEUM_ITEM"; else echo "MISSING $BINTEUM_ITEM"; fi
done
if [[ -x "$BINTEUM_SDK/emulator/emulator" ]]; then
  BINTEUM_AVDS="$("$BINTEUM_SDK/emulator/emulator" -list-avds)"
  if [[ -n "$BINTEUM_AVDS" ]]; then echo "$BINTEUM_AVDS"; else echo "MISSING AVD — create Pleos Connect 2.1.0 API 34, Cold boot, RAM 4 GB."; fi
else
  echo "MISSING emulator — install Pleos API 34 image and create an AVD in Android Studio."
fi
echo "No global shell settings were changed. Use scripts/gradle.sh for project commands."
