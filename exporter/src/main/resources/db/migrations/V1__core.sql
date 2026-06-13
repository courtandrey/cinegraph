-- ingest bookkeeping ---------------------------------------------------------
CREATE TABLE load_run (
    id            BIGSERIAL PRIMARY KEY,
    kind          TEXT NOT NULL CHECK (kind IN ('FULL','INCREMENTAL','EDGE_FULL','EDGE_INCREMENTAL')),
    status        TEXT NOT NULL CHECK (status IN ('RUNNING','COMPLETED','FAILED','CANCELLED')),
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    stats         JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- work queue for resumable fetching
CREATE TABLE fetch_queue (
    movie_id      BIGINT PRIMARY KEY,
    state         TEXT NOT NULL DEFAULT 'PENDING'
                  CHECK (state IN ('PENDING','IN_FLIGHT','DONE','FAILED','GONE')),
    attempts      SMALLINT NOT NULL DEFAULT 0,
    last_error    TEXT,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_fetch_queue_state ON fetch_queue(state) WHERE state IN ('PENDING','FAILED');

-- raw payloads ----------------------------------------------------------------
CREATE TABLE movie_raw (
    movie_id      BIGINT PRIMARY KEY,
    payload       JSONB NOT NULL,
    etag          TEXT,
    fetched_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- normalized projections -------------------------------------------------------
CREATE TABLE movie (
    movie_id          BIGINT PRIMARY KEY,
    title             TEXT NOT NULL,
    original_title    TEXT,
    release_date      DATE,
    release_year      SMALLINT,
    original_language TEXT,
    popularity        REAL,
    vote_average      REAL,
    vote_count        INTEGER,
    runtime           SMALLINT,
    poster_path       TEXT,
    overview          TEXT,
    countries         TEXT[] NOT NULL DEFAULT '{}',
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_movie_year ON movie(release_year);

CREATE TABLE genre (genre_id INT PRIMARY KEY, name TEXT NOT NULL);
CREATE TABLE movie_genre (
    movie_id BIGINT NOT NULL REFERENCES movie ON DELETE CASCADE,
    genre_id INT NOT NULL,
    PRIMARY KEY (movie_id, genre_id)
);
CREATE INDEX idx_movie_genre_genre ON movie_genre(genre_id);

CREATE TABLE keyword (keyword_id INT PRIMARY KEY, name TEXT NOT NULL);
CREATE TABLE movie_keyword (
    movie_id BIGINT NOT NULL REFERENCES movie ON DELETE CASCADE,
    keyword_id INT NOT NULL,
    PRIMARY KEY (movie_id, keyword_id)
);
CREATE INDEX idx_movie_keyword_kw ON movie_keyword(keyword_id);

CREATE TABLE person (
    person_id    BIGINT PRIMARY KEY,
    name         TEXT NOT NULL,
    profile_path TEXT
);

CREATE TABLE credit (
    movie_id    BIGINT NOT NULL REFERENCES movie ON DELETE CASCADE,
    person_id   BIGINT NOT NULL REFERENCES person,
    role        TEXT   NOT NULL,
    job         TEXT,
    department  TEXT,
    cast_order  SMALLINT,
    character   TEXT,
    PRIMARY KEY (movie_id, person_id, role)
);
CREATE INDEX idx_credit_person ON credit(person_id);

-- incremental dirty set -------------------------------------------------------
CREATE TABLE dirty_movie (
    run_id   BIGINT NOT NULL REFERENCES load_run,
    movie_id BIGINT NOT NULL,
    PRIMARY KEY (run_id, movie_id)
);

-- sync cursor -----------------------------------------------------------------
CREATE TABLE sync_state (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
