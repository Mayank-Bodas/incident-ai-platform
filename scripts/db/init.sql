-- This script runs ONCE when PostgreSQL container first starts.
-- WHY here and not Flyway?
-- pgvector extension must be installed before Flyway migrations run,
-- because V1 migration will use the vector type.
-- Flyway runs AFTER this init script.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
-- WHY uuid-ossp?
-- Generates UUID v4 values in SQL: uuid_generate_v4()
-- We use UUIDs as primary keys (not auto-increment integers).
-- WHY UUIDs over integers?
-- Globally unique = safe to merge data from multiple shards.
-- No sequential guessing = security benefit.
-- Downside: 16 bytes vs 4 bytes, worse index locality. Still the right choice at this scale.

CREATE EXTENSION IF NOT EXISTS vector;
-- WHY vector?
-- pgvector extension: stores and queries embedding vectors directly in PostgreSQL.
-- Avoids needing Pinecone, Weaviate, or Chroma as a separate service.
-- Cost of separate vector DB: $70-700+/month. pgvector: $0 (bundled with your DB).

