# ADR-001: Deterministic Sorted Locking vs. Heavier Concurrency Primitives

## Status
Accepted

## Context
In a high-concurrency P2P wallet system, multiple transactions concurrently read, validate, and mutate wallet balances. The system must guarantee that:
1. No wallet balance is overdrawn ($B \ge 0$).
2. Aggregate money is strictly conserved across all wallets.
3. Concurrent cross-transfers (e.g., Alice $\to$ Bob and Bob $\to$ Alice) never result in deadlocks or lost updates.

We must determine the simplest correct concurrency control mechanism in PostgreSQL.

## Decision
We implement **pessimistic row-level locking via `SELECT ... FOR UPDATE` with a deterministic ascending UUID sort order** across two explicit sequential queries.

```java
UUID lowerId  = id1.compareTo(id2) <= 0 ? id1 : id2;
UUID higherId = id1.compareTo(id2) <= 0 ? id2 : id1;

Wallet low  = jdbc.queryForObject("SELECT ... WHERE id = ? FOR UPDATE", ..., lowerId);
Wallet high = jdbc.queryForObject("SELECT ... WHERE id = ? FOR UPDATE", ..., higherId);
```

## Alternatives Considered & Rejected

### 1. Serializable Isolation (`ISOLATION_SERIALIZABLE`)
- **Mechanism**: Rely on PostgreSQL Serializable Snapshot Isolation (SSI) to abort transactions with read/write conflicts.
- **Why Rejected**: SSI does not prevent conflicts; it detects them after execution and throws serialization failures (SQLState `40001`). This shifts the burden onto the application layer to implement retry loops with exponential backoff. Under heavy contention (such as burst tests), serialization failure rates spike dramatically, degrading throughput and increasing p99 latency.

### 2. Single Conditional `UPDATE ... WHERE balance >= amount`
- **Mechanism**: Execute an atomic debit `UPDATE wallets SET balance = balance - :amt WHERE id = :from AND balance >= :amt` and inspect affected rows.
- **Why Rejected**: While conditional updates suffice for single-row debits, a P2P transfer involves two distinct entities plus double-entry ledger journal creation. If the debit succeeds but the credit or ledger insert fails, compensation logic is needed. Furthermore, you cannot inspect both sender and receiver balances atomically prior to mutation without locking both rows.

### 3. Application-Level Read-Check-Write
- **Mechanism**: Read balance into memory, perform validation in Java, then update.
- **Why Rejected**: Classic lost-update vulnerability. Two concurrent transactions reading balance = 1,000 can both approve a 1,000 debit, driving the balance to 0 while moving 2,000 paise (double-spend).

### 4. Distributed Locks via Redis (Redlock)
- **Mechanism**: Acquire distributed locks on wallet keys in Redis prior to database interaction.
- **Why Rejected**: Unnecessary operational complexity for a single primary relational database. Introduces network hops, clock skew vulnerabilities, lease expiration risks, and split-brain failure modes. PostgreSQL's native row locks are faster, strictly transactional, and automatically released on transaction commit/abort.

## Consequences
- **Positive**: Complete elimination of deadlocks; deterministic lock acquisition order; no application retry loops; transaction duration is strictly bounded (< 5ms).
- **Negative**: Requires two round-trips to lock both rows (negligible over connection pool).
