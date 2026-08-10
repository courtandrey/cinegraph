#!/usr/bin/env python3
import argparse
import json
import os
import random
import shlex
import subprocess
import sys
import time
import urllib.error
import urllib.request

SITE_URL = "https://cinegraphd.com"

POPULAR_SQL = """
SELECT movie_id, title, release_year
FROM movie
WHERE release_year IS NOT NULL
  AND release_date <= current_date
  AND poster_path IS NOT NULL
ORDER BY popularity DESC
LIMIT {limit}
"""


def env(name):
    value = os.environ.get(name)
    if not value:
        sys.exit(f"{name} is not set")
    return value


def popular_movies(limit):
    psql = shlex.split(os.environ.get("CINEGRAPH_PSQL", "psql"))
    proc = subprocess.run(
        psql + [env("CINEGRAPH_DB_DSN"), "-Atq", "-F", "\t", "-c", POPULAR_SQL.format(limit=limit)],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        sys.exit(f"psql failed: {proc.stderr.strip()}")
    movies = []
    for line in proc.stdout.splitlines():
        movie_id, title, year = line.split("\t")
        movies.append((int(movie_id), title, int(year)))
    return movies


def shortest_path(api_url, from_id, to_id, timeout):
    url = f"{api_url.rstrip('/')}/api/movies/{from_id}/path/{to_id}"
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:
        return {"found": False, "reason": f"http {e.code}"}
    except (urllib.error.URLError, TimeoutError) as e:
        return {"found": False, "reason": f"unreachable: {e}"}


def describe(result):
    nodes = result["nodes"]
    reasons = {(e["source"], e["target"]): e.get("topReason") for e in result["edges"]}
    lines = [f"{nodes[0]['title']} ({nodes[0]['year']})"]
    for previous, node in zip(nodes, nodes[1:]):
        reason = reasons.get((previous["id"], node["id"])) or reasons.get((node["id"], previous["id"]))
        lines.append(f"  ↓ {reason or '?'}")
        lines.append(f"{node['title']} ({node['year']})")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pool-from", type=int, default=500, help="rank window for the start film")
    parser.add_argument("--pool-to", type=int, default=1000, help="rank window for the target film")
    parser.add_argument("--min-years", type=int, default=20, help="minimum release-year gap")
    parser.add_argument("--min-hops", type=int, default=3, help="reject paths shorter than this")
    parser.add_argument("--attempts", type=int, default=50, help="pairs to try before giving up")
    parser.add_argument("--timeout", type=float, default=20.0, help="API timeout in seconds")
    parser.add_argument("--seed", type=int, help="RNG seed for reproducible picks")
    parser.add_argument("--quiet", action="store_true", help="print only the URL")
    args = parser.parse_args()

    api_url = env("CINEGRAPH_API_URL")
    random.seed(args.seed)

    pool = popular_movies(max(args.pool_from, args.pool_to))
    starts, targets = pool[:args.pool_from], pool[:args.pool_to]
    if not starts or not targets:
        sys.exit("popularity pools are empty")

    tried = set()
    for attempt in range(1, args.attempts + 1):
        start = random.choice(starts)
        eligible = [m for m in targets
                    if m[0] != start[0]
                    and abs(m[2] - start[2]) >= args.min_years
                    and (start[0], m[0]) not in tried]
        if not eligible:
            continue
        target = random.choice(eligible)
        tried.add((start[0], target[0]))

        result = shortest_path(api_url, start[0], target[0], args.timeout)
        hops = result.get("hops", 0)
        if not args.quiet:
            status = "found" if result.get("found") else result.get("reason")
            print(f"[{attempt}/{args.attempts}] {start[1]} ({start[2]}) → {target[1]} ({target[2]}): "
                  f"{status}, {hops} hops", file=sys.stderr)
        if result.get("found") and hops >= args.min_hops:
            if not args.quiet:
                print()
                print(describe(result))
                print()
            print(f"{SITE_URL}/path/{start[0]}/{target[0]}")
            return
        time.sleep(0.2)

    sys.exit(f"no path of >= {args.min_hops} hops found in {args.attempts} attempts")


if __name__ == "__main__":
    main()
