#!/bin/bash
# Delete the droplet kept by `KEEP=1 run.sh`, verify it is gone, cancel its deadline.
set -euo pipefail
cd "$(dirname "$0")/../.."
ST=$HOME/.local/state/gvpndroid-do
. <(sed 's/^/export /' "$ST/current")
# The token file the run used (recorded by run.sh); an older record has none, then $DO_TOKEN_FILE or the default.
if [ -n "${token_file:-}" ]; then export DO_TOKEN_FILE=$token_file; fi
DEADLINE=${DEADLINE:-$HOME/.claude/plugins/cache/sebs-ai-tools/ephemeral-cloud-run/0.1.1/scripts/deadline}
python3 -I scripts/do-build/droplet.py delete "$id"
python3 "$DEADLINE" cancel "$name"
mv "$ST/current" "$ST/current.deleted"
