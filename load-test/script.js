import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ----- metrics -----
const fraudRequests = new Counter('fraud_requests');
const fraudErrors   = new Rate('fraud_errors');
const fraudLatency  = new Trend('fraud_latency_ms', true);

// ----- config -----
const BASE_URL = __ENV.BASE_URL || 'http://localhost:9999';

// MAX_VUS env var lets you tune ceiling without editing the file.
// Default 100 fits inside Rinha's 350MB / 1 CPU constraint.
// Raise to 200 when profiling on unrestricted hardware.
const MAX_VUS = parseInt(__ENV.MAX_VUS || '100');

export const options = {
    stages: [
        { duration: '15s', target: Math.floor(MAX_VUS * 0.2) },  // ramp up
        { duration: '30s', target: Math.floor(MAX_VUS * 0.6) },  // build load
        { duration: '60s', target: MAX_VUS                    },  // sustained
        { duration: '30s', target: Math.floor(MAX_VUS * 0.6) },  // back off
        { duration: '15s', target: 0                          },  // ramp down
    ],
    thresholds: {
        http_req_failed:   ['rate<0.01'],   // <1% errors
        http_req_duration: ['p(95)<50'],    // p95 < 50ms
        fraud_errors:      ['rate<0.01'],
    },
};

// ----- payload generators -----
const MCCS = ['5411', '5912', '7995', '4111', '5812', '5999', '7011', '6011'];
const MERCHANTS = ['m-001', 'm-grocery', 'm-pharmacy', 'm-gambling', 'm-travel'];

function randBetween(min, max) {
    return Math.random() * (max - min) + min;
}

function randInt(min, max) {
    return Math.floor(randBetween(min, max));
}

function isoNow(offsetSeconds) {
    return new Date(Date.now() + offsetSeconds * 1000).toISOString().replace(/\.\d+Z$/, 'Z');
}

function lowRiskPayload() {
    const merchant = MERCHANTS[randInt(0, 3)]; // non-gambling
    const mcc = MCCS[randInt(0, 3)];
    const amount = randBetween(10, 300);
    return {
        id: `txn-low-${__VU}-${__ITER}`,
        transaction: {
            amount: parseFloat(amount.toFixed(2)),
            installments: randInt(1, 3),
            requested_at: isoNow(0),
        },
        customer: {
            avg_amount: parseFloat(randBetween(150, 400).toFixed(2)),
            tx_count_24h: randInt(1, 5),
            known_merchants: [merchant],
        },
        merchant: {
            id: merchant,
            mcc: mcc,
            avg_amount: parseFloat(randBetween(100, 350).toFixed(2)),
        },
        terminal: {
            is_online: false,
            card_present: true,
            km_from_home: parseFloat(randBetween(0, 10).toFixed(1)),
        },
        last_transaction: {
            timestamp: isoNow(-3600),
            km_from_current: parseFloat(randBetween(0, 5).toFixed(1)),
        },
    };
}

function highRiskPayload() {
    const amount = randBetween(5000, 15000);
    return {
        id: `txn-high-${__VU}-${__ITER}`,
        transaction: {
            amount: parseFloat(amount.toFixed(2)),
            installments: 1,
            requested_at: isoNow(0),
        },
        customer: {
            avg_amount: parseFloat(randBetween(50, 150).toFixed(2)),
            tx_count_24h: randInt(15, 30),
            known_merchants: ['m-unknown'],
        },
        merchant: {
            id: 'm-gambling-001',
            mcc: '7995',
            avg_amount: parseFloat(randBetween(8000, 12000).toFixed(2)),
        },
        terminal: {
            is_online: true,
            card_present: false,
            km_from_home: parseFloat(randBetween(500, 1500).toFixed(1)),
        },
        last_transaction: {
            timestamp: isoNow(-300),
            km_from_current: parseFloat(randBetween(400, 1000).toFixed(1)),
        },
    };
}

function mixedPayload() {
    return Math.random() < 0.7 ? lowRiskPayload() : highRiskPayload();
}

// ----- main scenario -----
const HEADERS = { 'Content-Type': 'application/json' };

export default function () {
    const payload = JSON.stringify(mixedPayload());
    const start = Date.now();

    const res = http.post(`${BASE_URL}/fraud-score`, payload, { headers: HEADERS });

    const elapsed = Date.now() - start;
    fraudRequests.add(1);
    fraudLatency.add(elapsed);

    const ok = check(res, {
        'status 200':         (r) => r.status === 200,
        'has approved field': (r) => r.body && r.body.includes('"approved"'),
        'has fraud_score':    (r) => r.body && r.body.includes('"fraud_score"'),
    });

    fraudErrors.add(!ok);
}

// ----- setup: verify /ready before test -----
export function setup() {
    const res = http.get(`${BASE_URL}/ready`);
    if (res.status !== 200) {
        throw new Error(`/ready returned ${res.status} — is the server up?`);
    }
    console.log(`Server ready at ${BASE_URL}`);
}
