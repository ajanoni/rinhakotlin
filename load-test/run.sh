#!/usr/bin/env bash
set -euo pipefail

# Usage: ./run.sh [base_url] [--docker]
# Examples:
#   ./run.sh                              # localhost:9999, local k6
#   ./run.sh http://localhost:9999        # explicit url, local k6
#   ./run.sh http://localhost:9999 --docker  # run k6 via docker

BASE_URL="${1:-http://localhost:9999}"
USE_DOCKER="${2:-}"
MAX_VUS="${MAX_VUS:-100}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Target: $BASE_URL  MAX_VUS=$MAX_VUS"

if [[ "$USE_DOCKER" == "--docker" ]]; then
    docker run --rm -i \
        --network host \
        -v "$SCRIPT_DIR:/scripts" \
        -e BASE_URL="$BASE_URL" \
        -e MAX_VUS="$MAX_VUS" \
        grafana/k6:latest run /scripts/script.js
else
    if ! command -v k6 &>/dev/null; then
        echo "k6 not found. Install: brew install k6  OR  pass --docker flag"
        exit 1
    fi
    BASE_URL="$BASE_URL" MAX_VUS="$MAX_VUS" k6 run "$SCRIPT_DIR/script.js"
fi