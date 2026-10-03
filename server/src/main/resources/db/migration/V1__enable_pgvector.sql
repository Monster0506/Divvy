-- pgvector is used from M2 onward for item embeddings. Only the extension is
-- enabled here; no vector columns exist yet.
-- Creating an extension needs superuser (or database owner on PG 13+ for trusted
-- extensions). The pgvector/pgvector Docker image's POSTGRES_USER satisfies this.
CREATE EXTENSION IF NOT EXISTS vector;