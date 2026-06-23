ALTER TABLE movie ADD COLUMN component_id BIGINT;
CREATE INDEX idx_movie_component ON movie(component_id);
