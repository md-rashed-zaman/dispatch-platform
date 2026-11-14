"""Times the technician search against the local dev database (50,000 seeded technicians).

Run from the repo root, with the stack up and the seed loaded (infra/seed/dev-seed.sql):

    python3 docs/perf/geo-search-bench.py

Each case runs 15 times inside a transaction that is rolled back, so dropping an index never sticks.
Prints the median execution time from EXPLAIN ANALYZE and the plan shape. Results: geo-search.md.
"""

import json
import statistics
import subprocess
import sys

RUNS = 15
WARMUP = 3

SITES = {
    "Gulshan, Dhaka (dense)": (90.4125, 23.7925),
    "Rangpur (sparse)": (89.2752, 25.7439),
}
SKILLS = {
    "networking": "ARRAY['networking']",
    "atm + solar + cctv": "ARRAY['atm', 'solar', 'cctv']",
}
DROP_INDEXES = "DROP INDEX technician_home_location_gix; DROP INDEX technician_skills_gin;"


def query(lon, lat, skills, radius_filter):
    site = f"ST_Point({lon}, {lat}, 4326)::geography"
    radius = {
        # The textbook version (T14).
        "ST_DWithin": f"ST_DWithin(home_location, {site}, 20000)"
        f" AND home_location <-> {site} <= service_radius_km * 1000",
        # What TechnicianSearch runs.
        "box + <->": f"home_location && ST_Buffer({site}, 20000 * 1.05)"
        f" AND home_location <-> {site} <= least(20000, service_radius_km * 1000)",
    }[radius_filter]
    return (
        f"SELECT id, full_name, skills, rating, home_location <-> {site} AS distance_m FROM technician"
        f" WHERE status = 'VERIFIED' AND skills @> {skills} AND {radius}"
        f" ORDER BY distance_m, id LIMIT 21"
    )


CASES = {
    "no indexes": (DROP_INDEXES, "ST_DWithin"),
    "indexes, ST_DWithin": ("", "ST_DWithin"),
    "indexes, box + <->": ("", "box + <->"),
}


def psql(sql):
    result = subprocess.run(
        ["docker", "compose", "-f", "infra/compose/docker-compose.yml", "exec", "-T", "postgres",
         "psql", "-U", "dispatch", "-d", "dispatch", "-At", "-v", "ON_ERROR_STOP=1"],
        input=sql, capture_output=True, text=True)
    if result.returncode:
        sys.exit(result.stderr)
    return result.stdout


def plans(output):
    decoder, position, found = json.JSONDecoder(), 0, []
    while (start := output.find("[", position)) >= 0:
        plan, position = decoder.raw_decode(output, start)
        found.append(plan[0])
    return found


def shape(node):
    name = node["Node Type"] + (f" ({node['Index Name']})" if "Index Name" in node else "")
    return name + "".join(" > " + shape(child) for child in node.get("Plans", []))


for site, (lon, lat) in SITES.items():
    for skill_name, skills in SKILLS.items():
        print(f"\n{site}, {skill_name}")
        for case, (setup, radius_filter) in CASES.items():
            explain = f"EXPLAIN (ANALYZE, FORMAT JSON) {query(lon, lat, skills, radius_filter)};\n"
            # Serial plans: parallel workers add noise of their own on a laptop.
            found = plans(psql(f"SET jit = off; SET max_parallel_workers_per_gather = 0;\nBEGIN; {setup}\n"
                               + explain * RUNS + "ROLLBACK;"))
            median = statistics.median(p["Execution Time"] for p in found[WARMUP:])
            print(f"  {case:22} {median:7.2f} ms   {shape(found[-1]['Plan'])}")
