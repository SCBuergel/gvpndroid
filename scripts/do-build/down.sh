#!/bin/bash
# Delete the droplet kept by `KEEP=1 run.sh`, verify it is gone, cancel its deadline.
set -euo pipefail
cd "$(dirname "$0")/../.."
ST=$HOME/.local/state/gvpndroid-do
. <(sed 's/^/export /' "$ST/current")
DEADLINE=${DEADLINE:-$HOME/.claude/plugins/cache/sebs-ai-tools/ephemeral-cloud-run/0.1.1/scripts/deadline}
python3 -I scripts/do-build/droplet.py delete "$id"
python3 "$DEADLINE" cancel "$name"
mv "$ST/current" "$ST/current.deleted"
