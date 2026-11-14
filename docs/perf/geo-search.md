# Technician geo search: 29 ms to 0.8 ms

The query behind `GET /api/technicians/nearby` and, from step 1.6, behind routing every published work order: **verified technicians with all the required skills, within 20 km of the site, whose own service radius reaches it, nearest first, 20 per page.**

Result on 50,000 technicians, for a site in Gulshan (the dense case: 22,500 technicians live within 20 km):

| | Median | Plan |
|---|---|---|
| No indexes | 29.5 ms | Seq Scan, then sort all 3,383 matches |
| GiST + GIN, textbook `ST_DWithin` query | 14.3 ms | Bitmap scans of both indexes, then sort all 3,383 matches |
| GiST + GIN, final query | **0.80 ms** | GiST index scan nearest-first, stops after 21 rows |

## Setup

- Data: `infra/seed/dev-seed.sql`, 50,000 technicians in the 8 divisional cities (45% in Dhaka), 1 to 3 of 10 skills each, 90% verified. Fixed random seed, so the numbers can be reproduced.
- Machine: 4-core laptop with 8 GB RAM; PostgreSQL 17.11, PostGIS 3.7.0 in Docker. The whole table fits in memory, so these numbers are CPU, not disk.
- Each case ran 15 times; the table shows the median after 3 warm-up runs. JIT and parallel workers are off: on this table they only add noise (JIT added 590 ms of compilation to a 50 ms query on the first run).
- Reproduce: `python3 docs/perf/geo-search-bench.py` from the repo root, with the stack up and the seed loaded.

## All cases

| Site, skills | No indexes | Indexes, `ST_DWithin` | Indexes, final query |
|---|---|---|---|
| Gulshan (dense), networking | 29.5 ms | 14.3 ms | **0.80 ms** |
| Gulshan (dense), atm + solar + cctv | 16.5 ms | 4.5 ms | **1.6 ms** |
| Rangpur (sparse), networking | 22.7 ms | 2.7 ms | **0.73 ms** |
| Rangpur (sparse), atm + solar + cctv | 16.7 ms | 1.9 ms | **1.8 ms** |

Through the API (Keycloak token, JSON, HTTP on the same laptop), 50 requests: median 15.5 ms, p95 27 ms. The database is now the smallest part of the request.

## What happened

### 1. Indexes alone barely helped where it matters

The textbook query:

```sql
WHERE status = 'VERIFIED' AND skills @> ARRAY['networking']
  AND ST_DWithin(home_location, :site, 20000)
ORDER BY home_location <-> :site, id LIMIT 21
```

With a GiST index on `home_location` and a GIN index on `skills`, Rangpur got 8x faster but Gulshan only 2x. Most of Dhaka is within 20 km of Gulshan, so the index still returns 22,500 rows, and Postgres then checks and sorts every match to keep the nearest 21:

```
Limit (actual rows=20)
  ->  Sort  Sort Key: (home_location <-> site), id   Sort Method: top-N heapsort
        ->  Bitmap Heap Scan on technician (rows=1 estimated, 3387 actual)
              ->  BitmapAnd
                    ->  Bitmap Index Scan on technician_skills_gin (actual rows=10046)
                    ->  Bitmap Index Scan on technician_home_location_gix (actual rows=22500)
```

### 2. The planner ignored the nearest-first plan because of one bad estimate

A GiST index can return rows **in distance order** (KNN, `ORDER BY home_location <-> :site`), so Postgres could read the 21 nearest matching technicians and stop. Forced with `enable_bitmapscan = off`, that plan takes 0.9 ms. Postgres didn't choose it because of the line above: **rows=1 estimated, 3,387 actual.** If only one row matches, sorting it is free and walking the index in order buys nothing.

Testing each condition on its own found the culprit:

| Condition | Estimated rows | Actual rows |
|---|---|---|
| `status = 'VERIFIED'` | 45,027 | 45,000 |
| `skills @> '{networking}'` | 10,068 | 10,046 |
| `home_location && <20 km box>` (what the index checks) | 22,422 | 22,500 |
| `ST_DWithin(home_location, site, 20000)` | **5** | 22,500 |

The index part of `ST_DWithin` is estimated well, but the function as a whole is estimated at 0.01% of the table. Fresh statistics (`ANALYZE`) don't change it.

### 3. The fix: write the radius check so the planner can estimate it

`TechnicianSearch` splits `ST_DWithin` into its two halves:

```sql
WHERE t.status = 'VERIFIED'
  AND t.skills @> :skills
  AND t.home_location && ST_Buffer(site, :radius * 1.05)                    -- index: box around the circle
  AND t.home_location <-> site <= least(:radius, t.service_radius_km * 1000) -- exact distance
ORDER BY distance_m, t.id
LIMIT :limit + 1
```

Now the estimate is in the thousands, and Postgres picks the nearest-first scan:

```
Limit (actual rows=21)
  ->  Incremental Sort  Sort Key: distance, id  Presorted Key: distance
        ->  Index Scan using technician_home_location_gix (actual rows=22)
              Index Cond: (home_location && st_buffer(site, 21000))
              Order By: (home_location <-> site)
              Filter: skills, status, exact distance   Rows Removed by Filter: 125
```

It reads 147 rows instead of 22,500. The `Incremental Sort` only orders technicians at exactly the same distance by id, the tie-breaker that keyset pagination needs.

For three rare skills the planner picks the GIN index instead (1.6 ms): few technicians have all three, so it finds them by skill and sorts the few hundred. That's the right choice too.

### Why the box is padded by 5%

`ST_Buffer` returns a polygon that is slightly *inside* the true circle, and PostGIS measures it on the ellipsoid while `<->` measures on a sphere. Unpadded, points 99.99% of the way to the edge fell outside the box in 14 to 18 of 360 directions, so the search would silently drop those technicians. With 5% padding, even points 1% *beyond* the edge were inside the box in all 1,440 directions tried, around 6 sites and for radii of 1, 20 and 100 km. The padding only widens the index's first pass; the exact `<->` check decides who matches.

`TechnicianSearchTests.includesTechniciansJustInsideTheRadiusInEveryDirection` places technicians 10 m inside and 10 m outside the edge in 24 directions. Remove the padding and it fails.

### The plan survives prepared statements

The app sends this query with bind parameters, and after 5 executions Postgres may switch to one generic plan for all parameter values. `EXPLAIN (GENERIC_PLAN)` shows the same nearest-first index scan.

## Known limits

- **Deeper pages cost more.** Page `n` re-walks the index from the nearest technician and skips the first `20 * n` rows, because a distance condition can't be an index condition. It's bounded by the search radius and still returns each technician exactly once. Matching only ever needs page 1.
- **Straight-line distance**, not travel time. Re-ranking the shortlist by road travel time (OSRM) is a later refinement.
- **The cursor reveals an exact distance.** See the `shortcut:` comment in `TechnicianSearch`.
