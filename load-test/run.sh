#!/usr/bin/env bash
set -euo pipefail

# Usage: ./run.sh [base_url] [scenario] [--docker]
#   base_url  default: http://localhost:9999
#   scenario  harness | saturate | smoke    (default: harness)
#   --docker  run k6 via docker instead of local binary
#
# Examples:
#   ./run.sh
#   ./run.sh http://localhost:9999 harness
#   ./run.sh http://localhost:9999 saturate
#   ./run.sh http://localhost:9999 harness --docker

BASE_URL="${1:-http://localhost:9999}"
SCENARIO="${2:-harness}"
USE_DOCKER="${3:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Generate payloads from dataset if not present
if [[ ! -f "$SCRIPT_DIR/payloads.json" ]]; then
    echo "==> payloads.json not found, running prepare.py …"
    if [[ ! -f "$SCRIPT_DIR/references.json" ]]; then
        echo "==> Downloading dataset files …"
        curl -sL https://raw.githubusercontent.com/zanfranceschi/rinha-de-backend-2026/main/resources/references.json.gz \
            -o "$SCRIPT_DIR/references.json.gz"
        gunzip "$SCRIPT_DIR/references.json.gz"
        curl -sL https://raw.githubusercontent.com/zanfranceschi/rinha-de-backend-2026/main/resources/mcc_risk.json \
            -o "$SCRIPT_DIR/mcc_risk.json"
        curl -sL https://raw.githubusercontent.com/zanfranceschi/rinha-de-backend-2026/main/resources/normalization.json \
            -o "$SCRIPT_DIR/normalization.json"
    fi
    python3 "$SCRIPT_DIR/prepare.py"
fi

echo "Target: $BASE_URL  scenario=$SCENARIO"

if [[ "$USE_DOCKER" == "--docker" ]]; then
    docker run --rm -i \
        --network host \
        -v "$SCRIPT_DIR:/scripts" \
        -e BASE_URL="$BASE_URL" \
        -e SCENARIO="$SCENARIO" \
        grafana/k6:latest run /scripts/script.js
else
    if ! command -v k6 &>/dev/null; then
        echo "k6 not found. Install: brew install k6  OR  pass --docker flag"
        exit 1
    fi
    BASE_URL="$BASE_URL" SCENARIO="$SCENARIO" k6 run "$SCRIPT_DIR/script.js"
fi
