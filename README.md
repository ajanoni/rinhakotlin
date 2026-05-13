# rinha-2026-kotlin

Kotlin/JVM fraud detection API for [Rinha de Backend 2026](https://github.com/zanfranceschi/rinha-de-backend-2026).

Architecture: 2 API workers behind a Kotlin load balancer, communicating over Unix domain sockets. All served on port `9999`.

```
client → LB :9999 → api1 (unix socket)
                  → api2 (unix socket)
```

## Prerequisites

- Docker + Docker Compose

## Build & Run

### 1. Build the Docker image

```bash
docker build --platform=linux/amd64 -t rinha-fraud-2026-kotlin:latest .
```

### 2. Start the stack

```bash
docker compose up -d
```

Services:
| Service | CPU | Memory | Role |
|---------|-----|--------|------|
| `api1`  | 0.4 | 160 MB | fraud scoring worker |
| `api2`  | 0.4 | 160 MB | fraud scoring worker |
| `lb`    | 0.2 |  30 MB | load balancer        |

### 3. Verify

```bash
curl http://localhost:9999/ready
# → HTTP 200
```

### 4. Stop

```bash
docker compose down
```

## Endpoints

### `GET /ready`

Health check. Returns `200 OK` when the model index is loaded and warm.

### `POST /fraud-score`

Scores a transaction for fraud risk.

**Request body:**
```json
{
  "id": "txn-001",
  "transaction": {
    "amount": 50.00,
    "installments": 1,
    "requested_at": "2025-01-15T14:30:00Z"
  },
  "customer": {
    "avg_amount": 200.0,
    "tx_count_24h": 1,
    "known_merchants": ["m-001"]
  },
  "merchant": {
    "id": "m-001",
    "mcc": "5411",
    "avg_amount": 180.0
  },
  "terminal": {
    "is_online": false,
    "card_present": true,
    "km_from_home": 1.0
  },
  "last_transaction": {
    "timestamp": "2025-01-15T10:00:00Z",
    "km_from_current": 0.5
  }
}
```

**Response:**
```json
{"approved": true, "fraud_score": 0.2}
```

## Load Test

Uses [k6](https://k6.io). Stages ramp from 50 → 200 → 500 → 1000 VUs.

### Option A — local k6

```bash
brew install k6
./load-test/run.sh
```

### Option B — k6 via Docker (no install needed)

```bash
./load-test/run.sh http://localhost:9999 --docker
```

### Custom target URL

```bash
./load-test/run.sh http://my-server:9999
```

### Custom VU ceiling

Default is 100 VUs, sized for Rinha's 350 MB / 1 CPU Docker limits. Raise it on unrestricted hardware:

```bash
MAX_VUS=200 k6 run load-test/script.js
# or
BASE_URL=http://localhost:9999 MAX_VUS=200 ./load-test/run.sh
```

### What it tests

- 70% low-risk / 30% high-risk randomized payloads
- Validates `approved` and `fraud_score` fields in every response
- Thresholds: **p95 < 50 ms**, **error rate < 1%**

## Integration Tests

Requires the stack to be running (`docker compose up -d`).

```bash
./gradlew test
```
