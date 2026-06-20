DELETE FROM edge e
WHERE NOT EXISTS (SELECT 1 FROM movie m WHERE m.movie_id = e.movie_a)
   OR NOT EXISTS (SELECT 1 FROM movie m WHERE m.movie_id = e.movie_b);

DELETE FROM credit c
WHERE NOT EXISTS (SELECT 1 FROM movie m WHERE m.movie_id = c.movie_id);

DELETE FROM movie_genre mg
WHERE NOT EXISTS (SELECT 1 FROM movie m WHERE m.movie_id = mg.movie_id);

DELETE FROM movie_keyword mk
WHERE NOT EXISTS (SELECT 1 FROM movie m WHERE m.movie_id = mk.movie_id);
