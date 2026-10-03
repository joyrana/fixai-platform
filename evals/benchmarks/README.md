# Benchmark baselines

Recorded with `python -m fixai_evals.cli benchmark ...` against the local compose stack (`--profile platform`).
Every run targets the synthetic simulator. Each file records parameters, environment (CPU count, commit) and metrics.

| File | Workload | Concurrency × runs | Time to verdict / report p50 (p95) | Throughput |
|---|---|---|---|---|
| `20261003T130952Z-benchmark-certification-throughput.json` | smoke suite (4 scenarios) | 4 × 12 | 3.2 s (3.5 s) | 72.6 runs/min, 290 scenarios/min |
| `20261003T131022Z-benchmark-certification-throughput.json` | full certification (24 scenarios) | 4 × 8 | 9.5 s (10.3 s) | 24.4 runs/min, 585 scenarios/min |
| `20261003T131031Z-benchmark-end-to-end.json` | AI workflow: plan → run → report | 3 × 6 | 3.8 s (4.1 s) | 46 workflows/min |

These runs used a 4-CPU container with every service and the simulator on one host, so they measure the engine and
platform overhead, not network latency to a real counterparty. A broker's TEST/UAT endpoint adds round trips and its
own pacing (for example heartbeat intervals and slow acknowledgements), so expect minutes, not seconds, for a full
external certification. Compare new runs against these files before claiming a performance change.
