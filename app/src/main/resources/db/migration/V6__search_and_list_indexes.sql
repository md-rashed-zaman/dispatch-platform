-- Indexes for the queries step 1.5 measured (docs/perf/geo-search.md), not guessed.
-- Plain CREATE INDEX is fine while there's no production data. On a live table, use
-- CREATE INDEX CONCURRENTLY in its own non-transactional migration, so writes aren't blocked (T13).

-- Technician search: GiST finds homes near a site and walks them nearest-first (ORDER BY <->);
-- GIN finds technicians whose skills array contains the required skills (@>).
CREATE INDEX technician_home_location_gix ON technician USING gist (home_location);
CREATE INDEX technician_skills_gin ON technician USING gin (skills);

-- Work order lists, newest first with id as the tie-breaker, matching the keyset pagination query
-- "WHERE tenant_id = ? [AND status = ?] AND (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC".
-- Equality columns first, then the sort columns (T13). The second index replaces (tenant_id, status).
CREATE INDEX work_order_tenant_created_idx ON work_order (tenant_id, created_at DESC, id DESC);
CREATE INDEX work_order_tenant_status_created_idx ON work_order (tenant_id, status, created_at DESC, id DESC);
DROP INDEX work_order_tenant_status_idx;
