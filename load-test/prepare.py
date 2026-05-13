#!/usr/bin/env python3
"""
Samples from references.json and converts 14-dim vectors back into
/fraud-score API payloads. Timestamps are omitted — k6 fills them at runtime.

Usage:
    python3 prepare.py [sample_size]
    Default sample_size = 20000
"""
import json
import random
import sys
import os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SAMPLE_SIZE = int(sys.argv[1]) if len(sys.argv) > 1 else 20000

# Harness fraud/legit ratio from test results
FRAUD_RATIO = 0.4447

# risk value -> MCC (from mcc_risk.json, rounded to 2dp to handle float comparison)
RISK_TO_MCC = {
    0.15: "5411",
    0.20: "5912",
    0.25: "5311",
    0.30: "5812",
    0.35: "4511",
    0.45: "5944",
    0.50: "5999",
    0.75: "7802",
    0.80: "7801",
    0.85: "7995",
}

def risk_to_mcc(v: float) -> str:
    return RISK_TO_MCC.get(round(v, 2), "5999")

# normalization constants (from normalization.json)
MAX_AMOUNT          = 10_000.0
MAX_INSTALLMENTS    = 12.0
AMOUNT_VS_AVG_RATIO = 10.0
MAX_MINUTES         = 1440.0
MAX_KM              = 1000.0
MAX_TX_COUNT_24H    = 20.0
MAX_MERCHANT_AVG    = 10_000.0

def vector_to_entry(vec: list[float], label: str, idx: int) -> dict:
    """
    Vector layout (matches Vectorizer.kt):
      0  amount / MAX_AMOUNT
      1  installments / MAX_INSTALLMENTS
      2  (amount / customerAvgAmount) / AMOUNT_VS_AVG_RATIO
      3  hour / 23
      4  dayOfWeek / 6
      5  minutesSinceLast / MAX_MINUTES   (-1 = no last tx)
      6  kmFromCurrent / MAX_KM           (-1 = no last tx)
      7  kmFromHome / MAX_KM
      8  txCount24h / MAX_TX_COUNT_24H
      9  isOnline (0/1)
     10  cardPresent (0/1)
     11  isUnknownMerchant (0/1)
     12  mccRisk
     13  merchantAvgAmount / MAX_MERCHANT_AVG
    """
    amount        = round(vec[0] * MAX_AMOUNT, 2)
    installments  = max(1, round(vec[1] * MAX_INSTALLMENTS))

    ratio = vec[2] * AMOUNT_VS_AVG_RATIO
    # avoid division by zero; if ratio ~0 customer avg is very large
    customer_avg  = round(amount / ratio, 2) if ratio > 0.001 else round(amount * 50, 2)

    hour          = round(vec[3] * 23)
    tx_count      = max(0, round(vec[8] * MAX_TX_COUNT_24H))

    has_last_tx       = vec[5] >= 0
    minutes_since     = round(vec[5] * MAX_MINUTES) if has_last_tx else -1
    km_from_current   = round(vec[6] * MAX_KM, 1) if has_last_tx else 0.0

    km_from_home  = round(vec[7] * MAX_KM, 1)
    is_online     = vec[9] > 0.5
    card_present  = vec[10] > 0.5
    is_unknown    = vec[11] > 0.5
    mcc           = risk_to_mcc(vec[12])
    merchant_avg  = round(vec[13] * MAX_MERCHANT_AVG, 2)

    # Reconstruct merchant / known_merchants
    if is_unknown:
        merchant_id     = f"m-unk-{idx % 200}"
        known_merchants = [f"m-known-{idx % 100}"]
    else:
        merchant_id     = f"m-known-{idx % 100}"
        known_merchants = [merchant_id]

    return {
        "label": label,
        "hour": hour,
        "minutes_since_last": minutes_since,   # -1 = no last_transaction
        "transaction": {
            "amount": amount,
            "installments": installments,
        },
        "customer": {
            "avg_amount": customer_avg,
            "tx_count_24h": tx_count,
            "known_merchants": known_merchants,
        },
        "merchant": {
            "id": merchant_id,
            "mcc": mcc,
            "avg_amount": merchant_avg,
        },
        "terminal": {
            "is_online": is_online,
            "card_present": card_present,
            "km_from_home": km_from_home,
        },
        "km_from_current": km_from_current,
    }


def main():
    refs_path = os.path.join(SCRIPT_DIR, "references.json")
    out_path  = os.path.join(SCRIPT_DIR, "payloads.json")

    print(f"Loading {refs_path} …")
    with open(refs_path) as f:
        references = json.load(f)

    fraud_refs = [r for r in references if r["label"] == "fraud"]
    legit_refs = [r for r in references if r["label"] == "legit"]

    n_fraud = round(SAMPLE_SIZE * FRAUD_RATIO)
    n_legit = SAMPLE_SIZE - n_fraud

    print(f"Sampling {n_fraud} fraud + {n_legit} legit from {len(references)} total …")

    sample_fraud = random.sample(fraud_refs, min(n_fraud, len(fraud_refs)))
    sample_legit = random.sample(legit_refs, min(n_legit, len(legit_refs)))
    sample = sample_fraud + sample_legit
    random.shuffle(sample)

    entries = [vector_to_entry(r["vector"], r["label"], i) for i, r in enumerate(sample)]

    with open(out_path, "w") as f:
        json.dump(entries, f, separators=(",", ":"))

    size_kb = os.path.getsize(out_path) // 1024
    print(f"Wrote {len(entries)} payloads → {out_path} ({size_kb} KB)")


if __name__ == "__main__":
    main()