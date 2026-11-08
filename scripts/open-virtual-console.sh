#!/usr/bin/env bash
set -euo pipefail
BINTEUM_SDK="${BINTEUM_ANDROID_SDK:-$HOME/Library/Android/sdk}"
BINTEUM_ADB="$BINTEUM_SDK/platform-tools/adb"
BINTEUM_SERIAL="${1:?Pass the verified Pleos emulator serial, for example emulator-5554}"
"$BINTEUM_ADB" -s "$BINTEUM_SERIAL" forward tcp:8088 tcp:8088
echo "Pleos Virtual Console: http://127.0.0.1:8088/"
echo "After testing: $BINTEUM_ADB -s $BINTEUM_SERIAL forward --remove tcp:8088"
