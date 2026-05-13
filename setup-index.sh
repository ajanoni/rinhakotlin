#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REFS_GZ="$SCRIPT_DIR/references.json.gz"
OUT_BIN="/tmp/rinha-index.bin"
OUT_GZ="$SCRIPT_DIR/src/main/resources/data/index.bin.gz"

REFS_URL="https://raw.githubusercontent.com/zanfranceschi/rinha-de-backend-2026/main/resources/references.json.gz"

cd "$SCRIPT_DIR"

# 1. Download
if [[ -f "$REFS_GZ" ]]; then
    echo "==> $REFS_GZ already exists, skipping download."
else
    echo "==> Downloading references.json.gz (~300MB)..."
    curl -L --progress-bar "$REFS_URL" -o "$REFS_GZ"
fi

# 2. Build index
echo "==> Building IVF index (k=4096, sample=50000, iters=10)..."
echo "    This takes several minutes."
./gradlew buildIndex --args="$REFS_GZ $OUT_BIN"

# 3. Compress and copy
echo "==> Compressing to $OUT_GZ..."
mkdir -p "$(dirname "$OUT_GZ")"
gzip -c "$OUT_BIN" > "$OUT_GZ"
rm -f "$OUT_BIN"

SIZE_MB=$(du -m "$OUT_GZ" | cut -f1)
echo ""
echo "Done. $OUT_GZ (${SIZE_MB}MB)"
