# Observability, Logging & Monitoring Guide

## 1. Structured JSON Logging Architecture

All log output is rendered as structured JSON via `logstash-logback-encoder` on standard output (`STDOUT`), captured natively by container runtimes and cloud aggregators (Render, Datadog, AWS CloudWatch).

### Correlation ID Threading
1. `CorrelationIdFilter` inspects incoming HTTP request headers for `X-Correlation-ID`.
2. If omitted, a fresh UUID is generated.
3. The ID is injected into SLF4J's **Mapped Diagnostic Context (MDC)** under key `correlation_id` and echoed in the HTTP response header `X-Correlation-ID`.
4. The authenticated user identity is simultaneously stored in MDC under key `user_id`.
5. Every subsequent log line emitted during that request lifecycle automatically inherits both fields.

---

## 2. Domain Event Log Catalog

### `wallet.created`
Emitted when a new wallet entity is initialized.
```json
{
  "ts": "2026-09-12T18:30:15.102Z",
  "level": "INFO",
  "service": "wallet-p2p-engine",
  "correlation_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "user_id": "alice",
  "event": "wallet.created",
  "wallet_id": "e81d432e-b4e2-49f2-8708-3eed60cd7b62",
  "message": "Wallet created"
}
```

### `transfer.completed`
Emitted upon successful atomic execution and ledger write.
```json
{
  "ts": "2026-09-12T18:32:44.892Z",
  "level": "INFO",
  "service": "wallet-p2p-engine",
  "correlation_id": "7f8b9c0d-1e2f-3a4b-5c6d-7e8f9a0b1c2d",
  "user_id": "alice",
  "event": "transfer.completed",
  "transfer_id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "from_wallet": "e81d432e-b4e2-49f2-8708-3eed60cd7b62",
  "to_wallet": "c3b2a109-8765-4321-fedc-ba9876543210",
  "amount_paise": 5000,
  "sender_balance_after": 45000,
  "receiver_balance_after": 15000,
  "message": "Transfer completed"
}
```

### `transfer.declined`
Emitted when sender funds are insufficient (no money moves).
```json
{
  "ts": "2026-09-12T18:35:02.341Z",
  "level": "INFO",
  "service": "wallet-p2p-engine",
  "correlation_id": "3a4b5c6d-7e8f-9a0b-1c2d-3e4f5a6b7c8d",
  "user_id": "alice",
  "event": "transfer.declined",
  "transfer_id": "d1e2f3a4-b5c6-7d8e-9f0a-1b2c3d4e5f6a",
  "reason": "insufficient_funds",
  "sender_balance": 4999,
  "requested_amount": 5000,
  "message": "Transfer declined: insufficient funds"
}
```

### `transfer.idempotent_replay`
Emitted when an identical transfer request is safely deduplicated.
```json
{
  "ts": "2026-09-12T18:36:11.710Z",
  "level": "INFO",
  "service": "wallet-p2p-engine",
  "correlation_id": "5c6d7e8f-9a0b-1c2d-3e4f-5a6b7c8d9e0f",
  "user_id": "alice",
  "event": "transfer.idempotent_replay",
  "transfer_id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "idempotency_key": "order-tx-987654",
  "original_status": "completed",
  "message": "Idempotent replay: returning original transfer"
}
```

---

## 3. Metrics Reference

| Metric Name | Type | Description | Tags |
|---|---|---|---|
| `transfers.completed` | Counter | Total successful transfers | `status=completed` |
| `transfers.declined` | Counter | Total transfers declined due to overdraft | `status=declined, reason=insufficient_funds` |
| `transfers.idempotent_replay` | Counter | Total duplicate transfer requests absorbed | `status=replay` |
| `wallets.created` | Counter | Total wallet entities initialized | None |
| `http.server.requests` | Timer | Request rate, duration, and percentile histograms | `uri, status, method, exception` |

### How to Access Metrics
- **Interactive Visual Dashboard**: `GET /dashboard` (HTML auto-refreshed every 5s).
- **Actuator JSON Metrics**: `GET /actuator/metrics/{metricName}`.
- **Prometheus Scrape Endpoint**: `GET /actuator/prometheus`.

---

## 4. Live Log Streaming on Render

To stream and filter logs in real time from Render:
1. Open the Render Dashboard $\to$ select `wallet-p2p-engine` service $\to$ click **Logs**.
2. **Filter by Event**: Search `event=transfer.declined` or `event=transfer.completed`.
3. **Trace Transaction**: Copy the `X-Correlation-ID` header from any response and filter by `correlation_id=<UUID>`.

---

## 5. Ledger Integrity Reconciliation Query

Run this query against the database at any time to verify that balance mutations match the append-only journal:

```sql
SELECT 
    w.id AS wallet_id,
    w.user_id,
    w.balance AS current_balance,
    COALESCE(SUM(
        CASE 
            WHEN l.direction = 'credit' THEN l.amount_paise 
            WHEN l.direction = 'debit'  THEN -l.amount_paise 
        END
    ), 0) AS journal_calculated_balance,
    w.balance - COALESCE(SUM(
        CASE 
            WHEN l.direction = 'credit' THEN l.amount_paise 
            WHEN l.direction = 'debit'  THEN -l.amount_paise 
        END
    ), 0) AS balance_discrepancy
FROM wallets w
LEFT JOIN ledger_entries l ON l.wallet_id = w.id
GROUP BY w.id, w.user_id, w.balance
HAVING w.balance != COALESCE(SUM(
    CASE 
        WHEN l.direction = 'credit' THEN l.amount_paise 
        WHEN l.direction = 'debit'  THEN -l.amount_paise 
    END
), 0);
```
*Expected: 0 rows returned. Any row indicates a conservation violation.*
