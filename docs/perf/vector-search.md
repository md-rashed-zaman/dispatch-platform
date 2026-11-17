# Vector search: exact vs HNSW, and why matching doesn't need the index

Step 3.4 ranks technicians partly by meaning: the cosine similarity between the job text's vector and each technician profile's vector (768 dimensions, `gemini-embedding-001`). Two ways to find the most similar vectors in PostgreSQL with pgvector:

- **Exact:** compute the distance to every vector, sort, take the top. Always right, cost grows with the table.
- **HNSW index:** walk a graph of "near" vectors from an entry point. Fast, but approximate: it can miss true neighbours. `hnsw.ef_search` sets how many candidates it keeps while walking: more means better recall, more time.

Result on 50,000 vectors, top 10 for 30 real job queries:

| Search | Recall@10 | Median | Max |
|---|---|---|---|
| Exact (sequential scan) | 1.000 | 362 ms | 1,865 ms |
| HNSW, `ef_search` 40 (the default) | **0.613** | 2.2 ms | 8 ms |
| HNSW, `ef_search` 100 | 0.800 | 4.1 ms | 38 ms |
| HNSW, `ef_search` 400 | 0.997 | 12.2 ms | 39 ms |
| **What matching does:** exact, on the 200 nearest technicians from the geo search | 1.000 | 16 ms | |

- HNSW is 30 to 160 times faster than a full exact scan, **but at the default setting it returned only 6 of the true top 10**. The vectors here sit in tight clusters of near-identical profiles (see Setup), the hardest case for a graph walk; recall is a number to measure on your own data, not a property of the index.
- Matching never searches all 50,000: it takes the 200 nearest verified technicians from the geo search (0.8 ms, [geo-search.md](geo-search.md)) and compares those exactly. A **pre-filter**: everyone in range is considered, recall is 1.0 by definition, and 16 ms is cheap next to the 1-2 s embedding call for the job. Searching the HNSW index first and then dropping technicians out of range (a **post-filter**) would return 10 "most similar" people from all over the country and, after the distance filter, often nobody nearby.
- The index is still worth having for questions without a location ("technicians who do Canon copiers, anywhere"), and it's where the table would need to go if candidate pools grew to many thousands.

| Cost | |
|---|---|
| Index build | 40 s for 50,000 vectors (`maintenance_work_mem` 512 MB) |
| Index size | 195 MB (3.9 KB per vector) |
| Table size with vectors | 201 MB, mostly TOAST: a 768-dimension vector is 3 KB, larger than PostgreSQL keeps inline |

## Setup

- **Vectors.** The free tier embeds at most 1,000 texts a day (docs/ai-limits.md), so 50,000 real profile embeddings would take 50 days. These are derived from the 115 real technician-profile embeddings of the matching eval (`evals/matching/recordings`): each of the 50,000 is one real vector plus uniform noise (±0.0175 per dimension), normalized; cosine to its source is about 0.96. Real structure, but with 435 near-duplicates per profile: realistic for "many technicians describe the same work similarly", harsher than typical data for HNSW.
- **Queries.** The 30 real job embeddings from the matching eval (task type RETRIEVAL_QUERY). Ground truth: exact search with index scans turned off.
- **Index.** pgvector 0.8, `USING hnsw (v vector_cosine_ops)`, default build settings (m = 16, ef_construction = 64).
- **Machine.** Same as geo-search.md: 4 cores, 8 GB, PostgreSQL 17 in Docker. Timings are warm (each run repeated after a full pass); the first, cold pass was 2-100 times slower while the 201 MB were read in.
- The derived table was a scratch table (`perf_embedding`), dropped afterwards; the app's own table is `technician_embedding` (V16), with the same index definition.
