# ADR-0007: In-process hybrid retrieval; pgvector deferred

- **Status:** Accepted
- **Date:** 2026-10-03

## Context

The knowledge agent needs retrieval over approved FIX and platform documentation, with:

- document-level ACLs and tenant isolation;
- provenance (document ID, version, SHA-256, chunk spans) for citations;
- quarantine of documents that contain prompt-injection indicators;
- reproducible evaluation.

The current corpus is a dozen synthetic documents (proprietary broker documents are never committed). A pgvector deployment adds an embedding-model dependency, a migration path and an operational surface. None of that is needed yet to measure retrieval quality or enforce access control.

## Decision

- **Index:** retrieval runs in the knowledge MCP server over an in-process index. It fuses BM25 with dense vectors by reciprocal-rank fusion. Vectors come from a deterministic 512-dimension feature-hashing embedding (`hash-512-v1`), so results are reproducible offline and in CI.
- **Governance at ingestion:** access control, tenant filtering and quarantine are applied when the index is built and again at query time. Citation quotes are verified server-side (`inspect_citations`).
- **Persistence:** the index can be serialised to disk (`KNOWLEDGE_INDEX_PATH`) with a manifest of document hashes. Re-ingestion is incremental (added, updated, unchanged, deleted).
- **Measurement:** retrieval quality is measured per mode (hybrid, lexical, vector) by the `rag-quality` suite. The suite reports recall@k, MRR, ACL leaks and quarantine enforcement.

## Consequences

- No external vector store or embedding API is needed to run the platform or its evaluations.
- Hashed embeddings capture lexical overlap, not semantics. Measured on the dataset (all splits, n=29), hybrid recall@1 is 0.83, lexical 0.76 and vector 0.69; recall@5 is 1.0 for all three modes. A semantic embedding model is the first lever if recall@1 must improve on larger corpora.
- **Trigger to revisit:** a corpus beyond about 10,000 chunks, multi-replica knowledge servers, or a semantic embedding model. The index interface (`upsert`, `delete`, `search`, `visible`) is the seam where pgvector would be introduced, with the `knowledge` schema that already exists in Postgres.
