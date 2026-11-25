import json
import sys
from pathlib import Path

ENDPOINTS = ["search", "film_detail", "film_graph", "shortest_path", "recommendations", "upload"]


def values(metrics, name):
    entry = metrics.get(name, {})
    return entry.get("values", entry)


def is_breached(result):
    return not result.get("ok") if isinstance(result, dict) else bool(result)


def breaches(metrics):
    return sorted(
        f"{name} {rule}"
        for name, entry in metrics.items()
        for rule, result in (entry.get("thresholds") or {}).items()
        if is_breached(result)
    )


def ms(value):
    if not value:
        return "–"
    return f"{value / 1000:.1f}s" if value >= 1000 else f"{value:.0f}ms"


def latency_cell(metrics, endpoint):
    trend = values(metrics, f"http_req_duration{{endpoint:{endpoint}}}")
    return f"{ms(trend.get('p(95)'))} / {ms(trend.get('max'))}"


def target_rps(run_id):
    return run_id.rsplit("-rps", 1)[-1]


def row(results_dir, run_id):
    path = results_dir / f"summary-{run_id}.json"
    if not path.exists():
        return f"| {target_rps(run_id)} | aborted, no summary |", None
    metrics = json.loads(path.read_text())["metrics"]
    broken = breaches(metrics)
    cells = [
        target_rps(run_id),
        "❌" if broken else "✅",
        f"{values(metrics, 'http_reqs').get('rate', 0):.0f}",
        str(values(metrics, "dropped_iterations").get("count", 0)),
        f"{100 * values(metrics, 'http_req_failed').get('value', 0):.2f}%",
        *(latency_cell(metrics, e) for e in ENDPOINTS),
    ]
    return "| " + " | ".join(cells) + " |", (target_rps(run_id), broken)


def main(results_dir, seq_id, run_ids):
    results_dir = Path(results_dir)
    columns = ["target RPS", "SLO", "avg req/s (whole run)", "dropped", "failed",
               *(f"{e} p95 / max" for e in ENDPOINTS)]
    lines = [f"# Stress sequence {seq_id}", "",
             "| " + " | ".join(columns) + " |",
             "|" + "---|" * len(columns)]
    outcomes = []
    for run_id in run_ids:
        line, outcome = row(results_dir, run_id)
        lines.append(line)
        if outcome:
            outcomes.append(outcome)

    passing = [rps for rps, broken in outcomes if not broken]
    lines += ["", f"Highest passing step: {passing[-1] + ' RPS' if passing else 'none'}"]
    lines += [f"- RPS {rps} breached: " + "; ".join(broken) for rps, broken in outcomes if broken]

    report = "\n".join(lines) + "\n"
    out = results_dir / f"sequence-{seq_id}.md"
    out.write_text(report)
    print(report)
    print(f"Report: {out}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3:])
