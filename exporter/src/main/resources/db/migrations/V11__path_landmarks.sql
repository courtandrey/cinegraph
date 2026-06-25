ALTER TABLE movie ADD COLUMN degree INTEGER;

CREATE TABLE landmark_distance (
    landmark_idx SMALLINT NOT NULL,
    movie_id     BIGINT   NOT NULL,
    dist         SMALLINT NOT NULL,
    PRIMARY KEY (movie_id, landmark_idx)
);
