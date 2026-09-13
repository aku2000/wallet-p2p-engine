# API Contract & Integration Reference

All monetary amounts are represented as **integers in paise** (1 INR = 100 paise).
Authentication is handled via HTTP Bearer token where the token string represents the `user_id`.

---

## Standard Error Response Envelope
Every error response returns a structured JSON payload accompanied by an `X-Correlation-ID` header:

```json
{
  "error": "INSUFFICIENT_FUNDS",
  "message": "Insufficient funds: balance=4999 paise, requested=5000 paise",
  "correlation_id": "8f3e2b1a-9c4d-4e7f-b8a2-1d5e6f7a8b9c"
}
```

---

## Endpoints

### 1. Get or Create Wallet
Initializes or fetches a wallet for the authenticated caller.

- **Method**: `POST`
- **Path**: `/wallets`
- **Headers**: `Authorization: Bearer <user_id>`
- **Response**: `201 Created`
```json
{
  "id": "e81d432e-b4e2-49f2-8708-3eed60cd7b62",
  "user_id": "alice",
  "balance": 0,
  "created_at": "2026-09-12T18:30:00Z"
}
```
- **Example cURL**:
```bash
curl -i -X POST "http://localhost:8080/wallets" \
  -H "Authorization: Bearer alice"
```

---

### 2. Get Wallet Balance
Queries current balance for a specified wallet.

- **Method**: `GET`
- **Path**: `/wallets/{id}`
- **Headers**: `Authorization: Bearer <user_id>`
- **Response**: `200 OK`
```json
{
  "id": "e81d432e-b4e2-49f2-8708-3eed60cd7b62",
  "user_id": "alice",
  "balance": 75000,
  "created_at": "2026-09-12T18:30:00Z"
}
```
- **Example cURL**:
```bash
curl -i -X GET "http://localhost:8080/wallets/e81d432e-b4e2-49f2-8708-3eed60cd7b62" \
  -H "Authorization: Bearer alice"
```

---

### 3. Execute Transfer
Executes an atomic money transfer from source wallet to target wallet.

- **Method**: `POST`
- **Path**: `/transfers`
- **Headers**:
  - `Authorization: Bearer <user_id>` (Must own `from` wallet)
  - `Content-Type: application/json`
  - `X-Correlation-ID: <optional-client-uuid>`
- **Request Body**:
```json
{
  "from": "e81d432e-b4e2-49f2-8708-3eed60cd7b62",
  "to": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "amount_paise": 2500,
  "idempotency_key": "order-tx-987654",
  "note": "Payment for lunch"
}
```
- **Responses**:
  - `201 Created`: Transfer newly processed and completed.
  - `200 OK`: Idempotent replay of a previously executed transfer.
  - `400 Bad Request`: Validation failure (self-transfer, non-positive amount, invalid UUID).
  - `401 Unauthorized`: Missing or malformed Bearer token.
  - `403 Forbidden`: Authenticated user does not own source wallet.
  - `404 Not Found`: Source or target wallet ID does not exist.
  - `409 Conflict`: Idempotency key reused with different parameters.
  - `422 Unprocessable Entity`: Insufficient balance in source wallet (cleanly declined).

```json
{
  "id": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
  "from_wallet_id": "e81d432e-b4e2-49f2-8708-3eed60cd7b62",
  "to_wallet_id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "amount_paise": 2500,
  "status": "completed",
  "idempotency_key": "order-tx-987654",
  "note": "Payment for lunch",
  "created_at": "2026-09-12T18:35:10Z"
}
```
- **Example cURL**:
```bash
curl -i -X POST "http://localhost:8080/transfers" \
  -H "Authorization: Bearer alice" \
  -H "Content-Type: application/json" \
  -d '{
    "from": "e81d432e-b4e2-49f2-8708-3eed60cd7b62",
    "to": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "amount_paise": 2500,
    "idempotency_key": "order-tx-987654",
    "note": "Payment for lunch"
  }'
```

---

### 4. Get Transfer Details
Retrieves status and metadata for a previously submitted transfer.

- **Method**: `GET`
- **Path**: `/transfers/{id}`
- **Headers**: `Authorization: Bearer <user_id>`
- **Response**: `200 OK`
```json
{
  "id": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
  "from_wallet_id": "e81d432e-b4e2-49f2-8708-3eed60cd7b62",
  "to_wallet_id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "amount_paise": 2500,
  "status": "completed",
  "idempotency_key": "order-tx-987654",
  "note": "Payment for lunch",
  "created_at": "2026-09-12T18:35:10Z"
}
```

---

### 5. Health Check Probe
Public liveness and readiness probe.

- **Method**: `GET`
- **Path**: `/health`
- **Auth**: None required (Public)
- **Response**: `200 OK`
```json
{
  "status": "UP",
  "service": "wallet-p2p-engine"
}
```

---

### 6. Real-Time Operational Dashboard
Interactive HTML dashboard displaying live metrics, invariant status, and system counters.

- **Method**: `GET`
- **Path**: `/dashboard`
- **Auth**: None required (Public)
- **Content-Type**: `text/html;charset=UTF-8`

