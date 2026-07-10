# CineGraph

A crew-based movie similarity graph. Search for a film, see its most similar films arranged
in concentric rings around it, click any edge to learn *why* two films are similar — same
director, shared cinematographer, overlapping genres — and re-center the graph on any node
to keep exploring.

Or upload your **Letterboxd** export and get the connected sub-graphs of everything you've
logged — each clustered around its most-connected film — then deep-dive into any film to
explore its neighbourhood restricted to your own watch history.

Similarity is computed offline for the entire TMDB movie catalog (~1M films) and stored as
a precomputed edge table, so the interactive API only ever reads (the sole exception being
the small per-upload Letterboxd film set it persists by file hash).

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
│  frontend  │◄──────────────│  graph-api    │  :8080  (read side +
│  Angular   │               │  reads, +     │   letterboxd_set writes)
└────────────┘               │  letterboxd   │
                             └──────────────┘
```

| Module | Role |
|---|---|
| `exporter/` | Ingests TMDB data (full + incremental), generates similarity edges. Owns all Flyway migrations. Port 8081. |
| `graph-api/` | REST API for search, movie details, graph payloads, edge breakdowns, and the Letterboxd graph builder. Read-only except for persisting uploaded Letterboxd film sets. Port 8080. |
| `graph-engine/` | Shortest-path service. Builds the whole movie graph into a CSR snapshot on disk and **memory-maps** it (the edge data is served from the OS page cache, not the Java heap — the JVM runs comfortably at `-Xmx512m` regardless of graph size), answering bidirectional-BFS path queries over **gRPC** (unhydrated ids + hops) — both whole-graph (`ShortestPath`) and constrained to an allowed node subset (`ShortestPathWithin`, used for Letterboxd-set paths). Internal-only: graph-api calls it and hydrates; the exporter triggers async rebuilds after edge changes. gRPC port 9090, actuator 8085. |
| `graph-proto/` | Shared gRPC contract (`path.proto`: `ShortestPath`, `ShortestPathWithin`, `Reload`) + generated stubs, depended on by graph-engine (server) and graph-api/exporter (clients). |
| `frontend/` | Angular 18 standalone components; Cytoscape.js graph canvas. Dev server on port 4200. |
| `db/migrations/` | Flyway SQL (V1–V7), executed by the exporter on startup. |

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
ranked too low to show. The min-score threshold still applies to the re-scored edges, and its
slider maximum is pinned just below the centre's strongest edge so the graph always keeps at
least one neighbour.

Every persisted edge stores a JSONB `components` array explaining the score — this is what
drives the "WHY SIMILAR" panel and edge tooltips in the UI.

**Find path** connects any two films by the fewest hops. From the centre film, searching a
destination calls `GET /api/movies/{from}/path/{to}`; graph-api asks graph-engine for the
shortest path over the whole movie graph and hydrates the returned ids into a node/edge chain.
The Letterboxd view has the same feature scoped to your film set (see below).

## Letterboxd graphs

From the search page, **Build a graph from your Letterboxd** accepts a CSV export
(`ratings.csv` or `watched.csv` from <https://letterboxd.com/user/exportdata/>). The flow:

1. **Resolve films → movie ids.** The CSV is parsed (jackson-dataformat-csv) and each row is
   matched in two passes: a single batched DB query resolves unique `title + year` matches;
   any conflict (no year, no match, or ambiguous) falls back to scraping that row's
   *Letterboxd URI* for its TMDB id, sequentially.
2. **Persist by content hash.** The upload is keyed by a SHA-256 of the file; the resolved
   `(hash, movie_id, rating)` rows are stored in `letterboxd_set` (graph-api's only write
   path). Re-uploading the same file skips resolution entirely and reuses the stored set.
3. **Overview graphs.** The induced sub-graph over the resolved films is split into connected
   components (orphans dropped, components under 5 nodes dropped, each capped to the largest
   `letterboxd.max-graph-nodes` by in-score). Each component is centred on its highest
   **in-score** node and returned biggest-first. In-score is the sum of a film's incident edge
   scores across the **whole component**, not just the rendered nodes — the node cap is a
   rendering optimization only, so a film's score stays correct even when the neighbours that
   earn it are hidden. The overview lays nodes out by in-score (closer = more connected) and
   offers an in-score slider to prune weakly-connected films.
4. **Deep-dive.** Clicking a film shows its details and total in-score; **Deep dive** re-centres
   on it via `POST /api/letterboxd/recenter` and drops into the same traversal UI as the search
   graph — re-centre, inspect edges, adjust weights — but every query is scoped to your film
   set (`…/recenter`, `…/reweight` take the hash). Nothing about the user graph is persisted
   beyond the hashed film set.
5. **Search & connect.** The find button searches your whole set (not just the rendered nodes).
   Picking a film reveals it; if it is connected in your set but capped out of the current view,
   the shortest path to it **inside the set** is fetched (see below) and the connecting films are
   pulled in so it never appears as a floating node. Films in no component at all are shown as
   *disconnected* and can't be selected — there is no path to reach them.
6. **Find path.** From a selected film, **Find path** searches any other film in the same graph
   (visible or not) and highlights the shortest path between them **within your set**: path nodes
   are enlarged, their edges lit, and the view fits to the path. The right panel lists the hop
   chain; **Dismiss** (or tapping any node) clears it. On phone the path surfaces a *show details*
   bar that opens a sheet with *collapse* / *dismiss*.

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

| Operation | Duration / size                 |
|---|---------------------------------|
| Full ingest (~1.05M movies @ 30 req/s) | 10–12 hours, resumable          |
| Full edge build | 1–3 hours                       |
| Edge table | 100M rows, ~80–90 GB with JSONB |

### Daily updates

A scheduled job (09:30 UTC) reads the TMDB changes API since the last sync, re-fetches only
changed movies, and incrementally regenerates just the edges touching them. It can also be
triggered manually via `POST /admin/incremental`.

## API

### graph-api (port 8080, public)

| Endpoint | Description |
|---|---|
| `GET /api/movies/search?q=&limit=` | Typeahead search: per-word prefix full-text match ranked by popularity, with a trigram word-similarity fallback for typos |
| `GET /api/movies/{id}` | Movie detail (title, year, genres, runtime, overview, …) |
| `GET /api/movies/{id}/graph?minScore=&limit=` | Graph payload from stored scores: center + top-N neighbors + inter-neighbor edges (each edge carries its score components) |
| `POST /api/movies/{id}/reweight` | Re-score all edges touching the center with custom weights (body `{limit, weights, minScore}`), return the new top-N |
| `GET /api/movies/{from}/path/{to}` | Fewest-hop path between two films over the whole graph (via graph-engine), hydrated to nodes + edges |
| `GET /api/edges/{a}/{b}` | Full similarity breakdown for one edge (side panel) |
| `GET /api/roles` | Role taxonomy with default base weights (drives the graph-weights editor) |
| `POST /api/letterboxd/graphs` | Upload a Letterboxd CSV (multipart `file`); resolves + persists the film set, returns `{hash, graphs}` |
| `GET /api/letterboxd/{hash}/graphs` | Rebuild the overview graphs from a previously uploaded set (no file needed) |
| `GET /api/letterboxd/{hash}/search?q=&limit=` | Typeahead over the films in a set, tagged with the component (`graphId`) each belongs to |
| `GET /api/letterboxd/{hash}/path?from=&to=` | Fewest-hop path between two films **constrained to the set** (graph-engine `ShortestPathWithin`); powers reveal-connect and Find path |
| `POST /api/letterboxd/attach` | Resolve a film's real in-score + its edges to the currently visible nodes (body `{hash, movieId, nodeIds}`) |
| `POST /api/letterboxd/recenter` | Center graph on a film, scoped to the set (body `{hash, movieId, minScore, limit}`) |
| `POST /api/letterboxd/reweight` | Re-score the set-scoped center graph with custom weights (body `{hash, movieId, limit, weights, minScore}`) |

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
| `scoring.min-crew-score` | 1.0     | Minimum crew component for an edge to persist |
| `scoring.per-person-cap` | 20.0    | Per-person crew score cap |
| `scoring.max-credits-per-person` | 800     | Hyper-prolific person cutoff |

Key graph-api settings (`application.yml`):

| Key | Default | Meaning |
|---|---------|---|
| `graph.per-person-cap` | 20.0    | Mirrors the exporter crew cap for re-weighting |
| `graph.max-edge-candidates` | 4000    | Edges scanned per re-weight request |
| `letterboxd.max-graph-nodes` | 500     | Per-component node cap for Letterboxd overview graphs |
| `letterboxd.min-graph-nodes` | 5       | Drop user sub-graphs smaller than this |
| `letterboxd.user-agent` | (Chrome UA) | Sent when scraping a Letterboxd film page for its TMDB id |

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
│       ├── db/migrations/   Flyway V1–V7 (schema, edge table, pg_trgm search, SQL aggregates, roles, letterboxd_set)
│       └── roles.yml        TMDB job → role code mapping + base weights
├── graph-api/       Spring Boot 3.3 — read API + Letterboxd graph builder (port 8080)
├── frontend/        Angular 18 + Cytoscape.js (dev server port 4200)
├── docker-compose.yml   PostgreSQL 16 with tuned settings
└── cinegraph-execution-plan.md   Full implementation spec
```
