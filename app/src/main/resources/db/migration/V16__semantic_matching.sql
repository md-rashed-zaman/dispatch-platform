-- Semantic matching (step 3.4, ADR 0013, T30).

-- What a technician says they do, in their own words: "8 years fixing HP and Canon printers and photocopiers".
-- Optional. Tags say which jobs a technician may take; the bio says which they're best at.
ALTER TABLE technician ADD COLUMN bio TEXT CHECK (length(bio) <= 500);

-- The matching module's copy of each technician's profile as a vector (skills and bio, embedded). Not
-- tenant-owned, like technician. source_hash is a hash of the embedded text: an update that doesn't change the
-- text doesn't need a new embedding. model names the embedding model: vectors from different models can't be
-- compared, so a new model means re-embedding everyone (the backfill job does it, newest first).
CREATE TABLE technician_embedding
(
  technician_id UUID PRIMARY KEY REFERENCES technician (id) ON DELETE CASCADE,
  embedding     VECTOR(768) NOT NULL,
  model         TEXT        NOT NULL,
  source_hash   TEXT        NOT NULL,
  embedded_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Approximate nearest neighbours by cosine distance, for searching all technicians by meaning. Matching itself
-- filters by distance first and compares a few hundred vectors exactly, which needs no index (docs/perf).
CREATE INDEX technician_embedding_hnsw_idx ON technician_embedding USING hnsw (embedding vector_cosine_ops);

-- The work order's text as a vector, computed once when it's routed and reused by the explain endpoint.
CREATE TABLE work_order_embedding
(
  work_order_id UUID PRIMARY KEY REFERENCES work_order (id) ON DELETE CASCADE,
  tenant_id     UUID        NOT NULL REFERENCES company (id),
  embedding     VECTOR(768) NOT NULL,
  model         TEXT        NOT NULL,
  source_hash   TEXT        NOT NULL,
  embedded_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Derived from a company's text, so it's that company's data.
ALTER TABLE work_order_embedding ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON work_order_embedding
  USING (tenant_id = current_tenant_id())
  WITH CHECK (tenant_id = current_tenant_id());

-- Reliability: how many of a technician's assigned jobs were completed, across every company. Row-level
-- security rightly hides other companies' work orders from the app, so this one aggregate runs as the table
-- owner (SECURITY DEFINER). It returns counts per technician only: no work order, company or text leaves.
CREATE FUNCTION technician_completion(ids UUID[])
  RETURNS TABLE (technician_id UUID, assigned INT, completed INT)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
  SELECT w.technician_id,
         count(*)::int,
         count(*) FILTER (WHERE w.status IN ('APPROVED', 'PAID'))::int
  FROM work_order w
  WHERE w.technician_id = ANY (ids)
  GROUP BY w.technician_id
$$;
REVOKE ALL ON FUNCTION technician_completion(UUID[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION technician_completion(UUID[]) TO dispatch_app;
