/**
 * Rinha 2026 – Fraud Detection Load Test
 *
 * Payloads sourced from references.json (rinha-de-backend-2026 dataset).
 * Run prepare.py first to generate payloads.json.
 *
 * Modes (SCENARIO env var):
 *   harness   – constant 900 RPS, 90 s  (mimics harness load)
 *   saturate  – ramp 50 → 500 VUs, find breaking point
 *   smoke     – 10 VUs, 30 s, sanity check
 *
 * Usage:
 *   SCENARIO=harness  k6 run script.js
 *   SCENARIO=saturate k6 run script.js
 *   BASE_URL=http://localhost:9999 SCENARIO=smoke k6 run script.js
 */
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

// ----- metrics -----
const httpErrors    = new Counter('rinha_http_errors');
const fraudApproved = new Counter('rinha_false_negatives');  // fraud approved = miss
const legitDenied   = new Counter('rinha_false_positives');  // legit denied = over-block
const reqLatency    = new Trend('rinha_latency_ms', true);
const errorRate     = new Rate('rinha_error_rate');

// ----- dataset -----
// SharedArray loads payloads.json once into shared memory across all VUs.
// Each entry: { label, hour, minutes_since_last, transaction, customer, merchant, terminal, km_from_current }
const PAYLOADS = new SharedArray('payloads', () => JSON.parse(open('./payloads.json')));

// ----- config -----
const BASE_URL = __ENV.BASE_URL || 'http://localhost:9999';
const SCENARIO = __ENV.SCENARIO || 'harness';

const SCENARIO_DEFS = {
    harness: {
        executor: 'constant-arrival-rate',
        rate: 900,
        timeUnit: '1s',
        duration: '90s',
        preAllocatedVUs: 300,
        maxVUs: 600,
    },
    saturate: {
        executor: 'ramping-vus',
        startVUs: 50,
        stages: [
            { duration: '10s', target: 100 },
            { duration: '20s', target: 200 },
            { duration: '20s', target: 350 },
            { duration: '30s', target: 500 },
            { duration: '10s', target: 0   },
        ],
    },
    smoke: {
        executor: 'constant-vus',
        vus: 10,
        duration: '30s',
    },
};

export const options = {
    scenarios: { load: SCENARIO_DEFS[SCENARIO] || SCENARIO_DEFS.harness },
    thresholds: {
        rinha_error_rate:  ['rate<0.05'],
        http_req_duration: ['p(99)<500'],
    },
    noConnectionReuse: false,
};

// ----- timestamp helpers -----
function isoWithHour(hour) {
    const d = new Date();
    d.setHours(hour, 0, 0, 0);
    return d.toISOString().replace(/\.\d+Z$/, 'Z');
}

function isoMinus(isoStr, seconds) {
    return new Date(new Date(isoStr).getTime() - seconds * 1000)
        .toISOString().replace(/\.\d+Z$/, 'Z');
}

// ----- request builder -----
function buildRequest(entry, vu, iter) {
    const requestedAt = isoWithHour(entry.hour);

    const lastTx = entry.minutes_since_last >= 0
        ? {
            timestamp: isoMinus(requestedAt, entry.minutes_since_last * 60),
            km_from_current: entry.km_from_current,
          }
        : null;

    return {
        id: `k6-${vu}-${iter}`,
        transaction: {
            amount: entry.transaction.amount,
            installments: entry.transaction.installments,
            requested_at: requestedAt,
        },
        customer: entry.customer,
        merchant: entry.merchant,
        terminal: entry.terminal,
        last_transaction: lastTx,
    };
}

// ----- main -----
const HEADERS = { 'Content-Type': 'application/json' };

export default function () {
    const entry = PAYLOADS[(__VU * 7919 + __ITER) % PAYLOADS.length];  // deterministic spread
    const body  = JSON.stringify(buildRequest(entry, __VU, __ITER));

    const res = http.post(`${BASE_URL}/fraud-score`, body, {
        headers: HEADERS,
        timeout: '10s',
    });

    reqLatency.add(res.timings.duration);

    const isHttpError = res.status === 0 || res.status >= 500 || res.status === 408;
    errorRate.add(isHttpError ? 1 : 0);

    if (isHttpError) {
        httpErrors.add(1);
        return;
    }

    // Parse response and track misclassifications
    let approved = null;
    try {
        approved = JSON.parse(res.body).approved;
    } catch (_) {}

    if (entry.label === 'fraud' && approved === true)  fraudApproved.add(1);
    if (entry.label === 'legit' && approved === false) legitDenied.add(1);

    check(res, {
        'status 200':      r => r.status === 200,
        'has approved':    r => r.body && r.body.includes('"approved"'),
        'has fraud_score': r => r.body && r.body.includes('"fraud_score"'),
    });
}

// ----- setup -----
export function setup() {
    const res = http.get(`${BASE_URL}/ready`, { timeout: '5s' });
    if (res.status !== 200) {
        throw new Error(`/ready returned ${res.status} — server not up at ${BASE_URL}`);
    }
    console.log(`[rinha] ready | scenario=${SCENARIO} | payloads=${PAYLOADS.length} | target=${BASE_URL}`);
}
