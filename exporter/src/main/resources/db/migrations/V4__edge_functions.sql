-- Aggregate that concatenates JSONB arrays, used to merge person arrays across pass-1 buckets.
CREATE OR REPLACE FUNCTION jsonb_arr_cat(a jsonb, b jsonb)
RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN a IS NULL THEN COALESCE(b, '[]'::jsonb)
        WHEN b IS NULL THEN a
        ELSE a || b
    END
$$;

CREATE OR REPLACE AGGREGATE jsonb_concat_agg(jsonb) (
    SFUNC    = jsonb_arr_cat,
    STYPE    = jsonb,
    INITCOND = '[]'
);
