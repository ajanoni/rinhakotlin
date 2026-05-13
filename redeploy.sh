#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

echo "==> Building jar..."
./gradlew shadowJar

echo "==> Building Docker image..."
docker build --platform=linux/amd64 -t rinha-fraud-2026-kotlin:latest .

echo "==> Restarting containers..."
docker compose down
docker compose up -d

echo "==> Waiting for /ready..."
until curl -sf http://localhost:9999/ready; do sleep 1; done
echo ""
echo "==> Done."
