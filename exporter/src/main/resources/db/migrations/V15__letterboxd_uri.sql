CREATE TABLE letterboxd_uri (
    uri         TEXT PRIMARY KEY,
    outcome     TEXT NOT NULL,
    movie_id    BIGINT,
    resolved_at TIMESTAMPTZ NOT NULL
);
