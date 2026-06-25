DROP TABLE IF EXISTS landmark_distance;
DROP INDEX IF EXISTS idx_movie_component;
ALTER TABLE movie DROP COLUMN IF EXISTS component_id;
