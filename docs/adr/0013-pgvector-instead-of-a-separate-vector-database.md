# 13. pgvector instead of a separate vector database

Date: 2026-11-18
Status: Accepted

## Context

Step 3.4 ranks technicians partly by meaning: each technician profile (skills and bio) and each work order's text becomes a 768-dimension vector (`gemini-embedding-001`). Ranking compares a job's vector with nearby technicians' vectors (cosine similarity) and combines that with skills, distance, rating and reliability (`HybridRanker`).

What the queries need:
- **Candidates come from a geo search.** Matching only ever considers the nearest verified technicians: 200 of them, from the PostGIS search that already exists ([geo-search.md](../perf/geo-search.md)).
- **Results join relational data.** Skills, rating, completed jobs, status, and row-level security for work-order vectors.
- **Scale is modest.** 50,000 technicians, one vector each (201 MB with the HNSW index).

## Decision

Store vectors in PostgreSQL with pgvector, in matching's own tables:
- `technician_embedding`: one vector per technician, plus the model and a hash of the embedded text.
- `work_order_embedding`: one per routed work order, under row-level security.

Rank with a **pre-filter**: the geo search picks the 200 nearest, then exact cosine similarity on those (16 ms). An HNSW index exists for searches without a location.

## Alternatives considered

| Option | Verdict |
|---|---|
| A vector database (Pinecone, Weaviate, Qdrant, Milvus) | Rejected for now: a second datastore to run, secure, back up and keep in sync with PostgreSQL (every profile change written twice, with a window where they disagree). Matching's filters (distance, status, skills, tenant) live in PostgreSQL; a vector store would either duplicate them or post-filter its results. Worth it at hundreds of millions of vectors or when vector search is the main workload; neither applies. |
| Approximate search first (HNSW over all technicians), then the distance filter (post-filter) | Rejected: the 10 most similar technicians in the country are rarely within 20 km, so after filtering there's often nobody. At the default `ef_search` HNSW also found only 6 of the true top 10 on our data ([vector-search.md](../perf/vector-search.md)). |
| Exact search over all vectors | Rejected: 362 ms median, 1.9 s worst, for no reason; the geo filter already narrows to 200. |
| Vectors as a column on `technician` | Rejected: the technician module would own data only matching uses, and a model change would rewrite the busiest table. A table per consumer keeps the module boundary and makes re-embedding a matter of matching's own rows. |
| Store vectors without the model name | Rejected: vectors from different models can't be compared. The model column makes a model change visible: every vector of the old model counts as missing, and the backfill replaces them. |

## Consequences

- One datastore. Backups, row-level security, transactions and joins work for vectors like for everything else.
- Re-embedding is matching's job: a Kafka consumer on `dispatch.technician.events.v1` re-embeds a changed profile (idempotent by text hash), and a paced backfill fills in what's missing. On the free tier (1,000 texts a day) a full re-embed of 50,000 profiles takes about 55 days; on a paid tier, minutes. Until a technician has a vector, ranking leaves out their semantic part instead of failing.
- The HNSW index costs 195 MB and 40 s to build for 50,000 vectors. Recall depends on `hnsw.ef_search`, which has to be set from measurements (0.997 at 400 in 12 ms).
- If candidate pools grow to many thousands, or vector search becomes the main workload, revisit: pgvector's filtered HNSW (iterative scans) first, a dedicated store after.
