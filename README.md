# CineGraph

A crew-based movie similarity graph. Search for a film, see its most similar films arranged
in concentric rings around it, click any edge to learn *why* two films are similar — same
director, shared cinematographer, overlapping genres — and re-center the graph on any node
to keep exploring.

Similarity is computed offline for the entire TMDB movie catalog (~1M films) and stored as
a precomputed edge table, so the interactive API only ever reads.

![Stack](https://img.shields.io/badge/Java-21-blue) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-green) ![Angular](https://img.shields.io/badge/Angular-18-red) ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)

## Architecture

Two Spring Boot applications share one PostgreSQL 16 database; an Angular SPA consumes the
read API.

```
┌────────────┐   TMDB API    ┌──────────────┐
│    TMDB    │◄──────────────│   exporter    │  :8081  (write side)
└────────────┘   30 req/s    │  ingest +     │
                             │  edge build   │
                             └──────┬───────┘
                                    │ writes
                             ┌──────▼───────┐
                             │ PostgreSQL 16 │  movie, credit, edge
                             └──────┬───────┘
                                    │ reads
┌────────────┐    REST       ┌──────▼───────┐
│  frontend  │◄──────────────│  graph-api    │  :8080  (read side)
│  Angular   │               │  read-only    │
└────────────┘               └──────────────┘
```

| Module | Role |
|---|---|
| `exporter/` | Ingests TMDB data (full + incremental), generates similarity edges. Owns all Flyway migrations. Port 8081. |
| `graph-api/` | Read-only REST API for search, movie details, graph payloads, and edge breakdowns. Port 8080. |
| `frontend/` | Angular 18 standalone components; Cytoscape.js graph canvas. Dev server on port 4200. |
| `db/migrations/` | Flyway SQL (V1–V4), executed by the exporter on startup. |

### Key design decisions

- **Raw JSONB is the source of truth.** `movie_raw.payload` keeps the full TMDB response;
  normalized tables (`movie`, `credit`, `movie_genre`, …) are projections. Retuning scoring
  weights and rebuilding all edges requires **no re-fetching**.
- **No JPA in the exporter.** All writes are explicit JDBC batches, one transaction per batch.
- **Single-writer ingest pipeline.** Virtual-thread fetchers feed a `BlockingQueue`; one
  dedicated writer thread drains it. Intentional serialization — no lock contention at ~30 movies/s.
- **Edge generation runs entirely in PostgreSQL.** Java orchestrates SQL across 64 hash
  buckets (`person_id % 64`); candidate rows never cross the JDBC boundary. Scratch tables
  (`scored_credit`, `edge_crew`) are `UNLOGGED` and rebuilt each run.
- **One TMDB request per movie.** `GET /3/movie/{id}?append_to_response=credits,keywords,release_dates`.
  Enumeration uses the daily ID export file, never `/discover`.
- **Resumable ingest.** A `fetch_queue` table tracks per-movie state (`PENDING → IN_FLIGHT →
  DONE/FAILED/GONE`); a crashed run is simply re-triggered.

## Scoring model

For each pair of movies, the total similarity score is the sum of four components:

| Component | Formula | Cap |
|---|---|--|
| Shared crew/cast | per shared person: `sameRole ? base × 1.5 : min(baseA, baseB)`, per-person cap 20 |
| Genre overlap | `2.0 × |shared genres|` | 6 |
| Keyword overlap | `0.5 × |shared keywords|` | 5 |
| Release proximity | `max(0, 4.0 × (1 − Δyears/25))` | 4 |

An edge is persisted whenever `crew_score ≥ min-crew-score` (default 1.0) — there is **no
total-score persist threshold**. The crew guard is the only filter, so pure genre/keyword
coincidence never creates an edge, but every meaningful co-credit is stored. Keeping the
edge table threshold-free is what lets re-weighting resurrect pairs that default scoring
would rank low.

Role base weights (single source of truth: `exporter/src/main/resources/roles.yml`):
`DIRECTOR 10`, `WRITER 8`, `DOP/EDITOR/COMPOSER 6`, `CAST_LEAD 5`, `PRODUCER/PRODUCTION_DESIGNER 4`,
`COSTUME_DESIGNER 3`, `CAST_SUPPORT 2`, `EXEC_PRODUCER 1.5`, `CAST_MINOR 0.75`, `CREW_OTHER 0.5`.
Each person counts once per movie with their highest-weighted role; persons with more than
800 credits are excluded as noise (TV-style mass producers).

The role taxonomy is **open-ended and persisted**: every normalized role code lives in the
`role` table (seeded from `roles.yml` at startup). Crew jobs not explicitly mapped get a
department-derived code — a gaffer becomes `LIGHTING`, a foley artist `SOUND` — at the
default weight 0.5, so any role can later be re-weighted (e.g. amplify `LIGHTING`) and the
graph rebuilt without re-fetching. `POST /admin/reproject` re-runs the projection of all
stored raw payloads after a rule change.

The frontend exposes this interactively: **Adjust graph weights** on the center film panel
opens a slider editor (0–20) for every role and correlation (genres, keywords, release
proximity) present in the visible graph. Hitting **Apply** sends the weights to
`POST /api/movies/{id}/reweight`, which re-scores **all** stored edges touching the centre
server-side (not just the ones already on screen), keeps those clearing the crew guard, and
returns the new top-`limit`. So amplifying a role can pull in films that the default scoring
ranked too low to show. The min-score threshold is disabled in this mode — only the result
limit applies.

Every persisted edge stores a JSONB `components` array explaining the score — this is what
drives the "WHY SIMILAR" panel and edge tooltips in the UI.

## Getting started

### Prerequisites

- JDK 21, Maven, Node 18+, Docker
- A TMDB API read access token (v4) — [get one here](https://www.themoviedb.org/settings/api)

### Run

```bash
docker-compose up -d

export TMDB_ACCESS_TOKEN=eyJ...
export ADMIN_TOKEN=changeme
mvn spring-boot:run -pl exporter

curl -X POST -H "X-Admin-Token: changeme" localhost:8081/admin/full-load

curl -X POST -H "X-Admin-Token: changeme" localhost:8081/admin/edges/rebuild

mvn spring-boot:run -pl graph-api

cd frontend && npm install && ng serve   # → http://localhost:4200
```

For a quick smoke test before committing to the full ~10-hour ingest, set
`ingest.limit-ids: 1000` in `exporter/src/main/resources/application.yml`.

### Scale expectations

| Operation | Duration / size |
|---|---|
| Full ingest (~1.05M movies @ 30 req/s) | 10–12 hours, resumable |
| Full edge build | 1–3 hours |
| Edge table | 10–40M rows, ~5–15 GB with JSONB |

### Daily updates

A scheduled job (09:30 UTC) reads the TMDB changes API since the last sync, re-fetches only
changed movies, and incrementally regenerates just the edges touching them. It can also be
triggered manually via `POST /admin/incremental`.

## API

### graph-api (port 8080, public)

| Endpoint | Description |
|---|---|
| `GET /api/movies/search?q=&limit=` | Typeahead search (prefix + trigram similarity) |
| `GET /api/movies/{id}` | Movie detail (title, year, genres, runtime, overview, …) |
| `GET /api/movies/{id}/graph?minScore=&limit=` | Graph payload from stored scores: center + top-N neighbors + inter-neighbor edges (each edge carries its score components) |
| `POST /api/movies/{id}/reweight` | Re-score all edges touching the center with custom weights (body `{limit, weights}`), return the new top-N |
| `GET /api/edges/{a}/{b}` | Full similarity breakdown for one edge (side panel) |
| `GET /api/roles` | Role taxonomy with default base weights (drives the graph-weights editor) |

### exporter admin (port 8081, requires `X-Admin-Token` header)

| Endpoint | Description |
|---|---|
| `POST /admin/full-load` | Start full ingest → `202` with run ID (`409` if already running) |
| `POST /admin/incremental` | Start incremental ingest |
| `POST /admin/retry-stuck` | Re-fetch stuck queue entries (IN_FLIGHT / FAILED) without re-seeding |
| `POST /admin/reproject` | Re-project all stored raw payloads through current rules (no fetching) |
| `GET /admin/runs/{id}` | Run progress (queue state counts, stats JSON) |
| `POST /admin/runs/{id}/cancel` | Request cooperative cancellation |
| `POST /admin/edges/rebuild` | Full edge rebuild |
| `POST /admin/edges/incremental?ingestRunId=` | Incremental edge maintenance for one ingest run |

## Configuration

Key exporter settings (`application.yml`):

| Key | Default | Meaning |
|---|---------|---|
| `tmdb.rate-limit.permits-per-second` | 30      | Global TMDB request budget |
| `tmdb.max-concurrency` | 64      | Concurrent in-flight TMDB requests |
| `ingest.limit-ids` | unset   | Cap seeded IDs (smoke tests) |
| `ingest.max-catchup-days` | 90      | Incremental refuses gaps larger than this |
| `scoring.persist-threshold` | 12.0    | Minimum total score for an edge |
| `scoring.min-crew-score` | 1.0     | Minimum crew component |
| `per-person-cap` | 20      | Crew score caps |
| `scoring.max-credits-per-person` | 800     | Hyper-prolific person cutoff |

## Build & test

```bash
mvn verify
mvn test -pl exporter -Dtest=FullLoadPipelineTest
cd frontend && ng test && ng build
```

## Project layout

```
cinemagraph/
├── exporter/        Spring Boot 3.3, Java 21 — ingest + edge build (port 8081)
│   └── src/main/resources/
│       ├── db/migrations/   Flyway V1–V4 (schema, edge table, pg_trgm search, SQL aggregates)
│       └── roles.yml        TMDB job → role code mapping + base weights
├── graph-api/       Spring Boot 3.3 — read-only REST API (port 8080)
├── frontend/        Angular 18 + Cytoscape.js (dev server port 4200)
├── docker-compose.yml   PostgreSQL 16 with tuned settings
└── cinegraph-execution-plan.md   Full implementation spec
```
