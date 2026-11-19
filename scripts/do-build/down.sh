#!/bin/bash
# Delete the droplet kept by `KEEP=1 run.sh`, verify it is gone, cancel its deadline.
set -euo pipefail
cd "$(dirname "$0")/../.."
ST=$HOME/.local/state/gvpndroid-do
. <(sed 's/^/export /' "$ST/current")
# The token file the run used (recorded by run.sh); an older record has none, then $DO_TOKEN_FILE or the default.
if [ -n "${token_file:-}" ]; then export DO_TOKEN_FILE=$token_file; fi
DEADLINE=${DEADLINE:-$HOME/.claude/plugins/cache/sebs-ai-tools/ephemeral-cloud-run/0.1.1/scripts/deadline}
python3 -I scripts/do-build/droplet.py delete "$id"   # set -e: a delete not verified keeps $ST/current
# Verified gone: clear the record even when the deadline already fired (it then removed its own record).
python3 "$DEADLINE" cancel "$name" || echo "deadline $name not cancelled: already fired or gone" >&2
mv "$ST/current" "$ST/current.deleted"
