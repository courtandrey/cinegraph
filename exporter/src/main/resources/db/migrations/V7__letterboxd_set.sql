CREATE TABLE letterboxd_set (
    hash     TEXT NOT NULL,
    movie_id BIGINT NOT NULL,
    rating   REAL,
    PRIMARY KEY (hash, movie_id)
);
