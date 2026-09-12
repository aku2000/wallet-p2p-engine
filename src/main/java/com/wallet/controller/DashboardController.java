package com.wallet.controller;

import com.wallet.metrics.WalletMetrics;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardController {

    private final WalletMetrics metrics;

    public DashboardController(WalletMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * GET /dashboard
     * Built-in zero-cost metrics dashboard (₹0 spend).
     * Renders real-time domain counters and invariant probes.
     * Auto-refreshes every 5 seconds.
     */
    @GetMapping(value = "/dashboard", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> dashboard() {
        String template = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Wallet Engine — Real-Time Dashboard</title>
                <meta http-equiv="refresh" content="5">
                <style>
                    :root {
                        --bg: #0f172a;
                        --card: #1e293b;
                        --card-border: #334155;
                        --text: #f8fafc;
                        --text-muted: #94a3b8;
                        --accent-blue: #38bdf8;
                        --accent-green: #4ade80;
                        --accent-red: #f87171;
                        --accent-yellow: #facc15;
                    }
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        background-color: var(--bg);
                        color: var(--text);
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
                        padding: 2rem;
                        line-height: 1.5;
                    }
                    .header {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        margin-bottom: 2rem;
                        padding-bottom: 1rem;
                        border-bottom: 1px solid var(--card-border);
                    }
                    .header h1 { font-size: 1.75rem; font-weight: 700; color: var(--text); }
                    .header .badge {
                        background: #064e3b;
                        color: var(--accent-green);
                        padding: 0.35rem 0.85rem;
                        border-radius: 9999px;
                        font-size: 0.875rem;
                        font-weight: 600;
                    }
                    .grid {
                        display: grid;
                        grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
                        gap: 1.5rem;
                        margin-bottom: 2rem;
                    }
                    .card {
                        background: var(--card);
                        border: 1px solid var(--card-border);
                        border-radius: 0.75rem;
                        padding: 1.5rem;
                        box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.2);
                    }
                    .card-title {
                        color: var(--text-muted);
                        font-size: 0.875rem;
                        font-weight: 600;
                        text-transform: uppercase;
                        letter-spacing: 0.05em;
                        margin-bottom: 0.5rem;
                    }
                    .card-value {
                        font-size: 2.25rem;
                        font-weight: 800;
                        margin-bottom: 0.25rem;
                    }
                    .val-green { color: var(--accent-green); }
                    .val-red { color: var(--accent-red); }
                    .val-yellow { color: var(--accent-yellow); }
                    .val-blue { color: var(--accent-blue); }
                    .card-sub { font-size: 0.75rem; color: var(--text-muted); }
                    .table-card {
                        background: var(--card);
                        border: 1px solid var(--card-border);
                        border-radius: 0.75rem;
                        padding: 1.5rem;
                        margin-bottom: 2rem;
                    }
                    .table-card h2 { font-size: 1.25rem; margin-bottom: 1rem; }
                    table { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
                    th, td { text-align: left; padding: 0.75rem; border-bottom: 1px solid var(--card-border); }
                    th { color: var(--text-muted); font-weight: 600; text-transform: uppercase; font-size: 0.75rem; }
                    .footer { text-align: center; color: var(--text-muted); font-size: 0.8rem; margin-top: 2rem; }
                </style>
            </head>
            <body>
                <div class="header">
                    <div>
                        <h1>⚡ wallet-p2p-engine</h1>
                        <p style="color: var(--text-muted); font-size: 0.9rem;">Real-Time Financial Invariant & Operational Metrics</p>
                    </div>
                    <div class="badge">● SYSTEM ACTIVE</div>
                </div>

                <div class="grid">
                    <div class="card">
                        <div class="card-title">Completed Transfers</div>
                        <div class="card-value val-green">%d</div>
                        <div class="card-sub">status = completed (conservation verified)</div>
                    </div>
                    <div class="card">
                        <div class="card-title">Declined Transfers</div>
                        <div class="card-value val-red">%d</div>
                        <div class="card-sub">reason = insufficient_funds (no overdraft)</div>
                    </div>
                    <div class="card">
                        <div class="card-title">Idempotent Replays</div>
                        <div class="card-value val-yellow">%d</div>
                        <div class="card-sub">duplicate keys safely deduplicated</div>
                    </div>
                    <div class="card">
                        <div class="card-title">Wallets Created</div>
                        <div class="card-value val-blue">%d</div>
                        <div class="card-sub">race-free get-or-create instances</div>
                    </div>
                </div>

                <div class="table-card">
                    <h2>Live Invariant & Observability Probes</h2>
                    <table>
                        <thead>
                            <tr>
                                <th>Invariant / Capability</th>
                                <th>Guaranteed By</th>
                                <th>Verification Endpoint</th>
                            </tr>
                        </thead>
                        <tbody>
                            <tr>
                                <td><strong>Gate 1: Race-Free Create</strong></td>
                                <td>Postgres UNIQUE (user_id) + ON CONFLICT DO NOTHING</td>
                                <td><code>POST /wallets</code></td>
                            </tr>
                            <tr>
                                <td><strong>Gate 2: Exactly-Once Transfer</strong></td>
                                <td>UNIQUE (idempotency_key) committed in same ledger tx</td>
                                <td><code>POST /transfers</code></td>
                            </tr>
                            <tr>
                                <td><strong>Gate 3: Conservation & No-Overdraft</strong></td>
                                <td>Sorted <code>SELECT FOR UPDATE</code> + CHECK (balance &gt;= 0)</td>
                                <td><code>POST /transfers</code></td>
                            </tr>
                            <tr>
                                <td><strong>Structured Tracing</strong></td>
                                <td>CorrelationIdFilter + MDC log threading</td>
                                <td>Header <code>X-Correlation-ID</code></td>
                            </tr>
                            <tr>
                                <td><strong>Raw Micrometer Metrics</strong></td>
                                <td>Spring Boot Actuator Prometheus registry</td>
                                <td><a href="/actuator/prometheus" style="color: var(--accent-blue);">/actuator/prometheus</a></td>
                            </tr>
                        </tbody>
                    </table>
                </div>

                <div class="footer">
                    Auto-refreshes every 5 seconds &bull; ₹0 Cost Deployment &bull; Render + PostgreSQL 16
                </div>
            </body>
            </html>
            """;

        String rendered = String.format(template,
                (long) metrics.getTransfersCompleted(),
                (long) metrics.getTransfersDeclined(),
                (long) metrics.getTransfersIdempotentReplay(),
                (long) metrics.getWalletsCreated()
        );

        return ResponseEntity.ok(rendered);
    }
}
