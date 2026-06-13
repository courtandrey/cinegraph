CREATE TABLE edge (
    movie_a     BIGINT NOT NULL,
    movie_b     BIGINT NOT NULL,
    total_score REAL   NOT NULL,
    crew_score  REAL   NOT NULL,
    components  JSONB  NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (movie_a, movie_b),
    CHECK (movie_a < movie_b)
);
CREATE INDEX idx_edge_a_score ON edge(movie_a, total_score DESC);
CREATE INDEX idx_edge_b_score ON edge(movie_b, total_score DESC);
