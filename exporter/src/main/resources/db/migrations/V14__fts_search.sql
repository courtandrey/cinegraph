CREATE INDEX idx_movie_fts ON movie
    USING gin (to_tsvector('simple', title || ' ' || coalesce(original_title, '')));
