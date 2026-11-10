#!/bin/sh
set -eu
BINTEUM_REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -n "${BINTEUM_NODE:-}" ]; then
  BINTEUM_NODE_BIN=$BINTEUM_NODE
elif command -v node >/dev/null 2>&1; then
  BINTEUM_NODE_BIN=$(command -v node)
elif [ -x /Applications/Codex.app/Contents/Resources/cua_node/bin/node ]; then
  BINTEUM_NODE_BIN=/Applications/Codex.app/Contents/Resources/cua_node/bin/node
else
  echo 'Node.js 22+ is needed. Set BINTEUM_NODE to its executable.' >&2
  exit 1
fi
cd "$BINTEUM_REPO_ROOT"
exec "$BINTEUM_NODE_BIN" --env-file-if-exists=server/.env server/src/index.mjs
