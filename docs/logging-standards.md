# Logging Standards

> This document is the authoritative logging specification for `wallet-p2p-engine`.
> All logging decisions must conform to these standards. If a log line doesn't fit
> a pattern defined here, add it to this document first.

---

## 1. Format: Structured JSON

All log output is structured JSON via `logstash-logback-encoder`.
**No plain-text log lines anywhere in this codebase.**

Every log line includes these fields automatically:

| Field | Source | Description |
|---|---|---|
| `ts` | Logback | ISO-8601 timestamp |
| `level` | Logback | `INFO`, `WARN`, `ERROR`, `DEBUG` |
| `logger` | Logback | Fully-qualified class name |
| `thread` | Logback | Thread name |
| `service` | `application.name` | Always `wallet-p2p-engine` |
| `correlation_id` | MDC (CorrelationIdFilter) | Per-request unique ID |
| `user_id` | MDC (BearerAuthFilter) | Authenticated user ID |
| `message` | Logger call | Human-readable description |

---

## 2. Log Levels

| Level | When to use | Examples |
|---|---|---|
| `DEBUG` | Internal state, dev-only, never in production | SQL bind parameters, lock acquisition steps |
| `INFO` | Domain events, request lifecycle, normal business operations | Transfer completed, wallet created |
| `WARN` | Recoverable issues, degraded behavior, retries | Idempotent replay hit, slow DB query |
| `ERROR` | Unhandled exceptions, system failures requiring attention | DB connection lost, unexpected null |

> **Rule:** In production (`prod` profile), `DEBUG` is disabled. All domain events are `INFO`.

---

## 3. MDC Context Fields

Two fields are always injected into the **Mapped Diagnostic Context (MDC)** by filters:

| MDC Key | Set By | Scope | Value |
|---|---|---|---|
| `correlation_id` | `CorrelationIdFilter` | Per request | From `X-Correlation-ID` header, or `UUID.randomUUID()` |
| `user_id` | `BearerAuthFilter` | Per request | Value of the Bearer token |

These automatically appear in **every** log line within a request — no need to manually include them.

The `correlation_id` is also:
- Sent back as a response header: `X-Correlation-ID`
- Included in all `ErrorResponse` bodies
- Used to trace a single request across all log lines

---

## 4. Domain Event Naming Convention

Domain events follow the format: **`<domain>.<action>`**

| Domain | Description |
|---|---|
| `wallet` | Wallet lifecycle events |
| `transfer` | Money movement events |
| `http` | HTTP request/response events |
| `system` | Application lifecycle events |

**Complete domain event catalog:**

### Wallet Events

| Event | Level | When |
|---|---|---|
| `wallet.created` | INFO | New wallet inserted (first time a user calls POST /wallets) |
| `wallet.fetched` | DEBUG | GET /wallets/{id} — balance read |

### Transfer Events

| Event | Level | When |
|---|---|---|
| `transfer.initiated` | INFO | Transfer request received, validation passed |
| `transfer.locks_acquired` | DEBUG | Both wallet rows locked in sorted order |
| `transfer.completed` | INFO | Money moved, ledger written, transaction committed |
| `transfer.declined` | INFO | Sender balance insufficient — no money moved |
| `transfer.idempotent_replay` | INFO | Same idempotency_key seen again — returning cached result |
| `transfer.idempotency_conflict` | WARN | Same key, different body — 409 returned |

### HTTP Events

| Event | Level | When |
|---|---|---|
| `http.request` | INFO | After each HTTP request completes (in RequestLoggingFilter) |

### System Events

| Event | Level | When |
|---|---|---|
| `system.startup` | INFO | Application started |
| `system.db_connected` | INFO | Database connection pool initialized |

---

## 5. Required Fields per Event

### `wallet.created`
```json
{
  "ts": "2026-09-12T14:00:00.000Z",
  "level": "INFO",
  "service": "wallet-p2p-engine",
  "correlation_id": "a1b2c3d4-...",
  "user_id": "alice",
  "event": "wallet.created",
  "wallet_id": "550e8400-e29b-41d4-a716-446655440000",
  "message": "Wallet created"
}
```

### `transfer.completed`
```json
{
  "ts": "2026-09-12T14:00:01.123Z",
  "level": "INFO",
  "service": "wallet-p2p-engine",
  "correlation_id": "a1b2c3d4-...",
  "user_id": "alice",
  "event": "transfer.completed",
  "transfer_id": "660e8400-...",
  "from_wallet": "550e8400-...",
  "to_wallet": "770e8400-...",
  "amount_paise": 5000,
  "sender_balance_after": 45000,
  "receiver_balance_after": 25000,
  "message": "Transfer completed"
}
```

### `transfer.declined`
```json
{
  "ts": "2026-09-12T14:00:01.456Z",
  "level": "INFO",
  "service": "wallet-p2p-engine",
  "correlation_id": "a1b2c3d4-...",
  "user_id": "alice",
  "event": "transfer.declined",
  "transfer_id": "660e8400-...",
  "reason": "insufficient_funds",
  "sender_balance": 4999,
  "requested_amount": 5000,
  "message": "Transfer declined: insufficient funds"
}
```

### `transfer.idempotent_replay`
```json
{
  "ts": "2026-09-12T14:00:01.789Z",
  "level": "INFO",
  "service": "wallet-p2p-engine",
  "correlation_id": "b2c3d4e5-...",
  "user_id": "alice",
  "event": "transfer.idempotent_replay",
  "transfer_id": "660e8400-...",
  "idempotency_key": "client-key-abc",
  "original_status": "completed",
  "message": "Idempotent replay: returning original transfer result"
}
```

### `transfer.idempotency_conflict`
```json
{
  "ts": "2026-09-12T14:00:02.000Z",
  "level": "WARN",
  "service": "wallet-p2p-engine",
  "correlation_id": "c3d4e5f6-...",
  "user_id": "alice",
  "event": "transfer.idempotency_conflict",
  "idempotency_key": "client-key-abc",
  "existing_transfer_id": "660e8400-...",
  "message": "Idempotency key reused with different request body"
}
```

### `http.request`
```json
{
  "ts": "2026-09-12T14:00:01.900Z",
  "level": "INFO",
  "service": "wallet-p2p-engine",
  "correlation_id": "a1b2c3d4-...",
  "user_id": "alice",
  "event": "http.request",
  "method": "POST",
  "path": "/transfers",
  "status": 201,
  "duration_ms": 47,
  "message": "POST /transfers 201 47ms"
}
```

---

## 6. Java Logging Pattern

Use `net.logstash.logback.argument.StructuredArguments.kv` for key-value pairs:

```java
import static net.logstash.logback.argument.StructuredArguments.kv;

// ✅ CORRECT — structured, queryable
log.info("Transfer completed",
    kv("event", "transfer.completed"),
    kv("transfer_id", transfer.id()),
    kv("from_wallet", transfer.fromWalletId()),
    kv("to_wallet", transfer.toWalletId()),
    kv("amount_paise", transfer.amountPaise()),
    kv("sender_balance_after", senderBalanceAfter)
);

// ✅ CORRECT — warn with context
log.warn("Idempotency conflict detected",
    kv("event", "transfer.idempotency_conflict"),
    kv("idempotency_key", key),
    kv("existing_transfer_id", existingTransferId)
);

// ❌ WRONG — unstructured string
log.info("Transfer " + transferId + " done, balance=" + balance);

// ❌ WRONG — missing event field (can't filter by domain event)
log.info("Something happened", kv("transfer_id", id));
```

---

## 7. What NEVER to Log

| Data | Reason |
|---|---|
| Full request/response bodies for financial operations | Potential PII, compliance risk |
| Passwords or secrets | Security |
| Raw card numbers or bank account numbers | PCI compliance |
| Stack traces at INFO level | Use `log.error("message", exception)` for traces |
| Balances of users other than the authenticated caller | Privacy |

```java
// ❌ WRONG — logs full request body (may contain sensitive data)
log.info("Request received: {}", requestBody.toString());

// ✅ CORRECT — log only the fields you need
log.info("Transfer initiated",
    kv("event", "transfer.initiated"),
    kv("from_wallet", request.from()),
    kv("amount_paise", request.amountPaise())
);
```

---

## 8. Error Logging

```java
// ✅ CORRECT — include exception for stack trace, structured context for filtering
log.error("Unexpected error processing transfer",
    kv("event", "transfer.error"),
    kv("transfer_id", transferId),
    exception   // last argument = Throwable → logback captures stack trace
);

// ✅ CORRECT — for expected/handled errors (no stack trace needed)
log.warn("Transfer declined",
    kv("event", "transfer.declined"),
    kv("reason", "insufficient_funds"),
    kv("sender_balance", balance),
    kv("requested_amount", amount)
);
```

---

## 9. Render.com Log Viewing

Logs are publicly viewable from the Render dashboard:
1. Open your Render service → **Logs** tab
2. Filter by `correlation_id` to trace a specific request
3. Filter by `event` to see all domain events of a type
4. Use `jq` locally for structured querying:
   ```bash
   # Stream logs and filter by event type
   cat app.log | jq 'select(.event == "transfer.declined")'

   # Trace a specific correlation_id
   cat app.log | jq 'select(.correlation_id == "abc-123")'
   ```

---

## 10. Reconciliation Log (Periodic Audit)

A reconciliation check can be logged on demand or scheduled to verify conservation:

```sql
-- Run this to detect any balance discrepancy
SELECT
    w.id,
    w.balance AS stored_balance,
    COALESCE(
        SUM(CASE WHEN l.direction='credit' THEN l.amount_paise
                 WHEN l.direction='debit'  THEN -l.amount_paise END),
        0
    ) AS ledger_balance,
    w.balance - COALESCE(
        SUM(CASE WHEN l.direction='credit' THEN l.amount_paise
                 WHEN l.direction='debit'  THEN -l.amount_paise END),
        0
    ) AS discrepancy
FROM wallets w
LEFT JOIN ledger_entries l ON l.wallet_id = w.id
GROUP BY w.id, w.balance
HAVING w.balance != COALESCE(
    SUM(CASE WHEN l.direction='credit' THEN l.amount_paise
             WHEN l.direction='debit'  THEN -l.amount_paise END),
    0
);
-- Should ALWAYS return 0 rows. Any row = bug.
```

