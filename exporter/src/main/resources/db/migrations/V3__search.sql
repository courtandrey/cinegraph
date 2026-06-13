CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_movie_title_trgm ON movie USING gin (title gin_trgm_ops);
CREATE INDEX idx_movie_orig_title_trgm ON movie USING gin (original_title gin_trgm_ops);
