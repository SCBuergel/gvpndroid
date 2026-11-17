#!/bin/sh
# Build, lint, unit-test and smoke-check the debug APK, with memory guards.
# Usage: scripts/build.sh            (assembleDebug lintDebug testDebugUnitTest + scripts/check-apk.sh)
#        scripts/build.sh TASK...    (only the given Gradle tasks)
# Refuses to start if less than MIN_AVAIL_MB of memory is available (override: FORCE=1).
set -eu
cd "$(dirname "$0")/.."
. "$HOME/android/env.sh"

MIN_AVAIL_MB=${MIN_AVAIL_MB:-2200}
avail=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)
swapfree=$(awk '/SwapFree/ {print int($2/1024)}' /proc/meminfo)
echo "memory available: ${avail} MB, swap free: ${swapfree} MB (need ${MIN_AVAIL_MB} MB)"
if [ "$avail" -lt "$MIN_AVAIL_MB" ] && [ "${FORCE:-0}" != 1 ]; then
    echo "not enough free memory for a Gradle build; refusing (FORCE=1 to override)" >&2
    exit 2
fi

# One build at a time on this machine, across all checkouts.
exec 9>"$HOME/android/.build.lock"
if ! flock -n 9; then
    echo "another Android build is running; refusing to start a second one" >&2
    exit 3
fi

full=0
[ $# -gt 0 ] || { set -- assembleDebug lintDebug testDebugUnitTest; full=1; }
# Hard cap: if the build overshoots, the kernel kills the build, not other processes.
CAP=${BUILD_MEMORY_MAX:-2300M}
if systemd-run --user --scope --quiet -p MemoryMax=64M true 2>/dev/null; then
    systemd-run --user --scope --quiet -p MemoryMax="$CAP" -p MemorySwapMax=0 \
        ./gradlew --no-daemon --max-workers=1 "$@"
else
    echo "warning: systemd user scope unavailable, building without a hard memory cap" >&2
    ./gradlew --no-daemon --max-workers=1 "$@"
fi
if [ "$full" = 1 ]; then
    scripts/check-apk.sh
fi
