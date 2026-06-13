-- Role taxonomy: every normalized role code observed in credits, with its scoring
-- weight. Seeded from roles.yml at startup; unmapped crew departments are inserted
-- dynamically with the default weight. Enables future per-role weight overrides
-- (custom graph recalculation).
CREATE TABLE role (
    code        TEXT PRIMARY KEY,
    base_weight REAL NOT NULL
);

-- Edge-build scratch tables, permanent UNLOGGED, truncated at the start of each run.
-- Previously created ad hoc per run; permanent definitions make them visible to the
-- jOOQ code generator and survive connection-pool reassignment.
CREATE UNLOGGED TABLE scored_credit (
    movie_id    BIGINT NOT NULL,
    person_id   BIGINT NOT NULL,
    role        TEXT   NOT NULL,
    base_weight REAL   NOT NULL
);
CREATE INDEX idx_scored_credit_person ON scored_credit(person_id);

CREATE UNLOGGED TABLE edge_crew_tmp (
    movie_a    BIGINT NOT NULL,
    movie_b    BIGINT NOT NULL,
    crew_score REAL   NOT NULL,
    persons    JSONB  NOT NULL
);

CREATE UNLOGGED TABLE edge_crew (
    movie_a    BIGINT NOT NULL,
    movie_b    BIGINT NOT NULL,
    crew_score REAL   NOT NULL,
    persons    JSONB  NOT NULL
);
CREATE INDEX idx_edge_crew_a ON edge_crew(movie_a);

CREATE UNLOGGED TABLE dirty_scope (
    movie_id BIGINT PRIMARY KEY
);

ALTER TABLE load_run DROP CONSTRAINT load_run_kind_check;
ALTER TABLE load_run ADD CONSTRAINT load_run_kind_check
    CHECK (kind IN ('FULL', 'INCREMENTAL', 'RETRY', 'REPROJECT', 'EDGE_FULL', 'EDGE_INCREMENTAL'));
