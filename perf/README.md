# Performance tests

Manual [k6](https://k6.io) load-test suite for the public CineGraph API. **Not wired into CI** — run it by hand against a deployed environment.

## Prerequisites

- [k6 installed](https://grafana.com/docs/k6/latest/set-up/install-k6/)
- `CINEGRAPH_HOST` pointing at the target origin (requests go to `$CINEGRAPH_HOST/api/...`)

## Usage

```bash
CINEGRAPH_HOST=https://cinegraphd.com ./run.sh
```

Outputs land in `perf/results/` (git-ignored):

| File | Contents |
|---|---|
| `run-<RUN_ID>.log` | Full k6 output |
| `summary-<RUN_ID>.json` | Machine-readable metrics summary |
| `hashes-<RUN_ID>.txt` | Hashes of the synthetic Letterboxd sets created by the run — **keep for cleanup** |

## What it does

1. **Seeding (setup):** runs ~30 searches against the live API and collects films whose `(title, year)` pair is unique within the results — no hardcoded IDs, works on any loaded environment. Then builds `LB_PARALLEL` CSVs in Letterboxd export format (`Name,Year,Letterboxd URI,Rating`) from the seeded pool — real titles and years, random ratings, a run-scoped nonce in the URI column so every upload gets a fresh hash. Each CSV's hash is precomputed (sha256 of the bytes, same as the server) so later scenarios can address the sets.
2. **Endpoint bursts** (each = `PARALLEL` simultaneous one-shot requests, staggered so endpoints are measured in isolation):
   - `search` — `GET /movies/search`
   - `film_detail` — `GET /movies/{id}`
   - `film_graph` — `GET /movies/{id}/graph?minScore=0&limit=40`
   - `shortest_path` — `GET /movies/{from}/path/{to}` (random pairs; `found:false` is a valid answer, only HTTP failures count)
   - `upload` — `POST /letterboxd/graphs` with the synthetic CSVs (`LB_PARALLEL` parallel); checks the server-returned hash matches the precomputed one and that graphs were built
   - `recommendations` — `GET /letterboxd/{hash}/recommendations?limit=25` against the uploaded sets (`LB_PARALLEL` parallel), scheduled after the upload slot (`upload start + SLO_UPLOAD_MS + 10s`)

Every response is checked for 2xx status and a sane body; any 4xx/5xx fails the run.

## SLOs (enforced as k6 thresholds — non-zero exit on breach)

| Scope | SLO |
|---|---|
| Every non-upload request (max, not percentile) | `< 5s` |
| Every synthetic-set upload | `< 60s` |
| HTTP failures (4xx/5xx/network) | `0` |
| Response-shape checks | `100%` |

## Configuration (env vars)

| Variable | Default | Meaning |
|---|---|---|
| `CINEGRAPH_HOST` | — (required) | Target origin |
| `PARALLEL` | `100` | Parallel calls per non-letterboxd endpoint |
| `LB_PARALLEL` | `10` | Parallel uploads / recommendation calls |
| `SET_SIZE` | `500` | Films per synthetic Letterboxd CSV |
| `SLO_MS` | `5000` | Non-upload SLO in ms |
| `SLO_UPLOAD_MS` | `60000` | Upload SLO in ms |
| `RUN_ID` | timestamp | Nonce embedded in synthetic CSVs and output file names |

Example — heavier run:

```bash
CINEGRAPH_HOST= PARALLEL=250 SET_SIZE=1000 ./run.sh
```

## Load models: `MODE=burst` vs `MODE=stress`

The suite has two load models; uploads + recommendations run the same way in both.

**`burst` (default)** — `PARALLEL` VUs fire one request each, per endpoint, simultaneously. This is a *closed* model: when the server slows down, the in-flight requests just take longer and no new load arrives — good for SLO verification at a known concurrency, but it self-throttles and therefore **cannot show you the collapse point**.

**`stress`** — an *open* model (`ramping-arrival-rate`): k6 injects a mixed traffic profile (35% search / 25% detail / 30% graph / 10% path) at a target rate regardless of how slowly responses come back, exactly like real traffic. Latency growth and `dropped_iterations` (k6 ran out of VUs to keep the rate — i.e. the server has fallen behind the offered load) show you whether pressure means "slower" or "DoS":

```bash
CINEGRAPH_HOST=https://cinegraphd.com MODE=stress RPS=300 RAMP=2m HOLD=3m ./run.sh
```

| Variable | Default | Meaning |
|---|---|---|
| `RPS` | `300` | Target requests/second at plateau |
| `RAMP` / `HOLD` | `2m` / `3m` | Ramp-up time and time held at `RPS` |
| `PRE_VUS` | `RPS` | Pre-allocated VU pool |
| `MAX_VUS` | `RPS*4` | Hard VU cap; when latency makes in-flight exceed this, iterations are dropped (and counted) |

The required VU pool follows Little's law: in-flight ≈ RPS × avg latency in seconds. 300 RPS at 1s latency needs ~300 VUs — not 10 000. Find the knee by re-running with increasing `RPS` (or make the stages steeper) and watching where p99 and `dropped_iterations` take off.

## Running big tests on one machine

- **VUs are expensive; requests are not.** Each VU is a full JS runtime (~0.5–1MB) **plus its own deep copy of the `setup()` data**. The suite keeps setup data down to the seeded film pool (~100KB) — synthetic CSVs are regenerated deterministically inside upload VUs instead of being passed around, so 10k VUs cost ~1–2GB, not tens of GB.
- Raise the file-descriptor limit and widen the ephemeral port range before large runs:
  ```bash
  ulimit -n 1048576
  sudo sysctl -w net.ipv4.ip_local_port_range="1024 65535"
  ```
- **Run the generator on a different machine than the server.** Loading `localhost` makes k6 and the API compete for the same CPUs, which caps the offered load and corrupts the latency numbers.
- A tuned single machine handles roughly 10–30k VUs / tens of kRPS. Beyond that, k6 distributes: shard the same script across machines with `--execution-segment '0:1/3'` / `'1/3:2/3'` / `'2/3:1'`, run it on Kubernetes via [k6-operator](https://github.com/grafana/k6-operator), or use Grafana Cloud k6.

## Cleaning up synthetic sets

Each run creates `LB_PARALLEL` Letterboxd sets. Their hashes are in `results/hashes-<RUN_ID>.txt`. Remove them from the database with:

```sql
DELETE FROM letterboxd_set WHERE hash IN ('<hash1>', '<hash2>', ...);
```

## Notes

- Scenario start times are staggered 30s apart; if an endpoint blows far past its SLO the next burst may overlap with its tail — by then the run has already failed its threshold, so the overlap only affects the magnitude of the numbers, not the verdict.
- The graph engine must be up for `shortest_path` and engine-served recommendations; without it, paths 500 and the run fails (as it should for a production check).
