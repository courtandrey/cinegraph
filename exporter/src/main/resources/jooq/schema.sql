CREATE TABLE load_run (
    id          BIGSERIAL PRIMARY KEY,
    kind        VARCHAR NOT NULL,
    status      VARCHAR NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    stats       JSONB NOT NULL
);

CREATE TABLE fetch_queue (
    movie_id   BIGINT PRIMARY KEY,
    state      VARCHAR NOT NULL,
    attempts   SMALLINT NOT NULL,
    last_error VARCHAR,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE movie_raw (
    movie_id   BIGINT PRIMARY KEY,
    payload    JSONB NOT NULL,
    etag       VARCHAR,
    fetched_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE movie (
    movie_id          BIGINT PRIMARY KEY,
    title             VARCHAR NOT NULL,
    original_title    VARCHAR,
    release_date      DATE,
    release_year      SMALLINT,
    original_language VARCHAR,
    popularity        REAL,
    vote_average      REAL,
    vote_count        INTEGER,
    runtime           SMALLINT,
    poster_path       VARCHAR,
    overview          VARCHAR,
    countries         VARCHAR(255) ARRAY NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL
);

CREATE TABLE genre (
    genre_id INT PRIMARY KEY,
    name     VARCHAR NOT NULL
);

CREATE TABLE movie_genre (
    movie_id BIGINT NOT NULL,
    genre_id INT NOT NULL,
    PRIMARY KEY (movie_id, genre_id)
);

CREATE TABLE keyword (
    keyword_id INT PRIMARY KEY,
    name       VARCHAR NOT NULL
);

CREATE TABLE movie_keyword (
    movie_id   BIGINT NOT NULL,
    keyword_id INT NOT NULL,
    PRIMARY KEY (movie_id, keyword_id)
);

CREATE TABLE person (
    person_id    BIGINT PRIMARY KEY,
    name         VARCHAR NOT NULL,
    profile_path VARCHAR
);

CREATE TABLE credit (
    movie_id   BIGINT NOT NULL,
    person_id  BIGINT NOT NULL,
    role       VARCHAR NOT NULL,
    job        VARCHAR,
    department VARCHAR,
    cast_order SMALLINT,
    character  VARCHAR,
    PRIMARY KEY (movie_id, person_id, role)
);

CREATE TABLE dirty_movie (
    run_id   BIGINT NOT NULL,
    movie_id BIGINT NOT NULL,
    PRIMARY KEY (run_id, movie_id)
);

CREATE TABLE sync_state (
    key   VARCHAR PRIMARY KEY,
    value VARCHAR NOT NULL
);

CREATE TABLE edge (
    movie_a     BIGINT NOT NULL,
    movie_b     BIGINT NOT NULL,
    total_score REAL NOT NULL,
    crew_score  REAL NOT NULL,
    components  JSONB NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (movie_a, movie_b)
);

CREATE TABLE role (
    code        VARCHAR PRIMARY KEY,
    base_weight REAL NOT NULL
);

CREATE TABLE scored_credit (
    movie_id    BIGINT NOT NULL,
    person_id   BIGINT NOT NULL,
    role        VARCHAR NOT NULL,
    base_weight REAL NOT NULL
);

CREATE TABLE edge_crew_tmp (
    movie_a    BIGINT NOT NULL,
    movie_b    BIGINT NOT NULL,
    crew_score REAL NOT NULL,
    persons    JSONB NOT NULL
);

CREATE TABLE edge_crew (
    movie_a    BIGINT NOT NULL,
    movie_b    BIGINT NOT NULL,
    crew_score REAL NOT NULL,
    persons    JSONB NOT NULL
);

CREATE TABLE dirty_scope (
    movie_id BIGINT PRIMARY KEY
);
