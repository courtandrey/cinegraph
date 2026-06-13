-- Allow the RETRY run kind: re-fetches stuck fetch_queue entries
-- (IN_FLIGHT leftovers / retriable FAILED) without re-seeding from the ID export.
ALTER TABLE load_run DROP CONSTRAINT load_run_kind_check;
ALTER TABLE load_run ADD CONSTRAINT load_run_kind_check
    CHECK (kind IN ('FULL', 'INCREMENTAL', 'RETRY', 'EDGE_FULL', 'EDGE_INCREMENTAL'));
