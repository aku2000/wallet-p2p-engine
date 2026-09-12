# ADR-004: Consistency vs. Availability for a Financial Ledger Workload

## Status
Accepted

## Context
Under Eric Brewer's CAP Theorem, any distributed data store can satisfy at most two of three guarantees: Consistency, Availability, and Partition Tolerance. When network partitions or primary database failovers occur, systems must choose between:
- **CP (Consistency + Partition Tolerance)**: Reject transactions to preserve correctness.
- **AP (Availability + Partition Tolerance)**: Accept transactions against local replicas or degraded state at the cost of stale reads and conflicting writes.

## Decision
We explicitly choose **Strong Consistency (CP)** over high availability.
All state mutations (wallet balances, transfers, and ledger entries) require synchronous confirmation from the primary PostgreSQL instance with `READ COMMITTED` transaction isolation paired with explicit row locks (`FOR UPDATE`).

## What We Consciously Give Up
During network partitions, PostgreSQL failovers, or primary database maintenance windows:
- The wallet engine will fail incoming write requests and return HTTP 500 / 503 errors.
- We deliberately give up continuous 100% uptime (availability) during infrastructure degradation.
- We reject optimistic local writes, asynchronous balance synchronization, and offline payment queues.

## Justification in Financial Systems
In an e-commerce catalog or social media feed, eventual consistency is acceptable: a user seeing an outdated like count or review does no material harm.

In a financial wallet system:
- If we prioritize availability and accept transfers while disconnected from the authoritative ledger, an attacker can initiate concurrent transfers across multiple partitioned nodes and multiply their funds (double-spend).
- An unavailable service produces a temporary retry error for the end-user (transient inconvenience).
- An inconsistent service produces fraudulent balance inflation, negative ledger discrepancies, and direct monetary loss that cannot be reconciled automatically.

## Consequences
- **Positive**: Strict financial invariants are always preserved; zero double-spending; balances are authoritative at all times.
- **Negative**: The service availability SLA is strictly bounded by the primary database availability SLA.
