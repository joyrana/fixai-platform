"""Statistics for reporting: proportions with Wilson intervals, latency percentiles, variance across trials."""

from __future__ import annotations

import math
import statistics


def wilson(successes: int, total: int, z: float = 1.96) -> tuple[float, float]:
    if total == 0:
        return (0.0, 0.0)
    p = successes / total
    centre = (p + z * z / (2 * total)) / (1 + z * z / total)
    half = z * math.sqrt(p * (1 - p) / total + z * z / (4 * total * total)) / (1 + z * z / total)
    return (round(max(0.0, centre - half), 4), round(min(1.0, centre + half), 4))


def proportion(successes: int, total: int) -> dict[str, float | int | tuple[float, float]]:
    return {"value": round(successes / total, 4) if total else 0.0, "n": total, "successes": successes,
            "ci95": wilson(successes, total)}


def percentiles(values: list[float]) -> dict[str, float]:
    if not values:
        return {"p50": 0.0, "p95": 0.0, "p99": 0.0, "mean": 0.0, "n": 0}
    ordered = sorted(values)

    def pct(q: float) -> float:
        index = min(len(ordered) - 1, max(0, math.ceil(q * len(ordered)) - 1))
        return round(ordered[index], 3)

    return {"p50": pct(0.50), "p95": pct(0.95), "p99": pct(0.99), "mean": round(statistics.fmean(ordered), 3),
            "n": len(ordered)}


def variance_across_trials(per_trial_scores: list[float]) -> dict[str, float]:
    if len(per_trial_scores) < 2:
        return {"stdev": 0.0, "trials": len(per_trial_scores)}
    return {"stdev": round(statistics.stdev(per_trial_scores), 4), "trials": len(per_trial_scores)}
