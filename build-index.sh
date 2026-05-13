#!/usr/bin/env bash
set -euo pipefail

REFS="${1:-references.json.gz}"
OUT_BIN="${2:-/tmp/index.bin}"
OUT_GZ="src/main/resources/data/index.bin.gz"

if [[ ! -f "$REFS" ]]; then
    echo "ERROR: $REFS not found. Download it first:"
    echo "  curl -L -o references.json.gz <url>"
    exit 1
fi

echo "==> Building index from $REFS..."
./gradlew buildIndex --args="$REFS $OUT_BIN"

echo "==> Compressing to $OUT_GZ..."
mkdir -p src/main/resources/data
gzip -c "$OUT_BIN" > "$OUT_GZ"

SIZE_MB=$(du -m "$OUT_GZ" | cut -f1)
echo "==> Done. $OUT_GZ (${SIZE_MB}MB)"
echo "==> Run ./redeploy.sh to rebuild and restart."
