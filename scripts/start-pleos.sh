#!/usr/bin/env bash
set -euo pipefail
BINTEUM_SDK="${BINTEUM_ANDROID_SDK:-$HOME/Library/Android/sdk}"
BINTEUM_AVD="Binteum_Pleos_2_1_API34"
BINTEUM_EMULATOR="$BINTEUM_SDK/emulator/emulator"
if [[ ! -x "$BINTEUM_EMULATOR" ]]; then
  echo "Android Emulator is missing. Set BINTEUM_ANDROID_SDK to the installed SDK." >&2
  exit 1
fi
BINTEUM_AVDS="$("$BINTEUM_EMULATOR" -list-avds)"
BINTEUM_FOUND=false
while IFS= read -r BINTEUM_NAME; do
  if [[ "$BINTEUM_NAME" == "$BINTEUM_AVD" ]]; then BINTEUM_FOUND=true; fi
done <<< "$BINTEUM_AVDS"
if [[ "$BINTEUM_FOUND" != true ]]; then
  echo "Create the Pleos API 34 AVD named $BINTEUM_AVD first." >&2
  exit 1
fi
# This Mac needs Vulkan disabled with hardware GLES rendering.
# Keep this scoped to this launch; do not change global emulator settings.
exec "$BINTEUM_EMULATOR" -avd "$BINTEUM_AVD" -memory 4096 \
  -no-snapshot-load -no-snapshot-save -gpu host -feature -Vulkan -no-metrics "$@"
