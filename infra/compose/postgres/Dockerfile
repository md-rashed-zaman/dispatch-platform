# pgvector image plus PostGIS: Dispatch needs vector search and geo search in one database.
# (postgis/postgis:17-* is on Debian bullseye, whose PostgreSQL apt repo is retired, so we start from pgvector.)
FROM pgvector/pgvector:pg17-bookworm

RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-17-postgis-3 \
    && rm -rf /var/lib/apt/lists/*
