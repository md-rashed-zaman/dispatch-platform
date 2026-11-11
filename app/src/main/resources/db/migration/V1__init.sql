-- Extensions every later migration relies on.
CREATE EXTENSION IF NOT EXISTS postgis;   -- geo search for technicians (step 1.5)
CREATE EXTENSION IF NOT EXISTS vector;    -- semantic matching with embeddings (step 3.4)
