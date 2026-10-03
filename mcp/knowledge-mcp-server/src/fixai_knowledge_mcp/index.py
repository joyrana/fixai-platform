"""Hybrid retrieval index (BM25 + hashed-embedding cosine, fused with reciprocal rank) with ACL filtering.

The embedding is a deterministic feature-hashing model (`hash-512-v1`): no external service, reproducible in CI.
It is recorded in the index manifest so a change of embedding model forces a re-index. A pgvector store is deferred
until corpus size justifies it (ADR-0007).
"""

from __future__ import annotations

import hashlib
import json
import math
from collections import Counter
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Literal

from fixai_knowledge_mcp.documents import CHUNKING_CONFIGS, Chunk, ChunkingConfig, Document, chunk, parse, tokens

EMBEDDING_MODEL = "hash-512-v1"
DIMENSIONS = 512
Mode = Literal["hybrid", "lexical", "vector"]


def embed(text: str) -> list[float]:
    vector = [0.0] * DIMENSIONS
    words = tokens(text)
    features = words + [f"{a}_{b}" for a, b in zip(words, words[1:], strict=False)]
    for feature in features:
        digest = hashlib.blake2b(feature.encode(), digest_size=8).digest()
        bucket = int.from_bytes(digest[:4], "big") % DIMENSIONS
        sign = 1.0 if digest[4] & 1 else -1.0
        vector[bucket] += sign
    norm = math.sqrt(sum(v * v for v in vector)) or 1.0
    return [v / norm for v in vector]


@dataclass(frozen=True)
class Hit:
    chunk: Chunk
    score: float
    lexical_rank: int | None
    vector_rank: int | None


@dataclass
class Manifest:
    embedding_model: str
    chunking: str
    documents: dict[str, str]
    """doc_id -> sha256 of the indexed version."""


class InMemoryIndex:
    def __init__(self, chunking: ChunkingConfig) -> None:
        self.chunking = chunking
        self.documents: dict[str, Document] = {}
        self.chunks: dict[str, Chunk] = {}
        self._vectors: dict[str, list[float]] = {}
        self._tf: dict[str, Counter[str]] = {}
        self._df: Counter[str] = Counter()

    # -- ingestion ---------------------------------------------------------------------------------
    def upsert(self, document: Document) -> Literal["added", "updated", "unchanged"]:
        existing = self.documents.get(document.doc_id)
        if existing and existing.sha256 == document.sha256 and existing.version == document.version:
            return "unchanged"
        if existing:
            self.delete(document.doc_id)
        self.documents[document.doc_id] = document
        for piece in chunk(document, self.chunking):
            self.chunks[piece.chunk_id] = piece
            self._vectors[piece.chunk_id] = embed(f"{piece.title} {piece.section} {piece.text}")
            terms = Counter(tokens(f"{piece.section} {piece.text}"))
            self._tf[piece.chunk_id] = terms
            self._df.update(terms.keys())
        return "updated" if existing else "added"

    def delete(self, doc_id: str) -> bool:
        if doc_id not in self.documents:
            return False
        del self.documents[doc_id]
        for chunk_id in [c for c, piece in self.chunks.items() if piece.doc_id == doc_id]:
            self._df.subtract(self._tf[chunk_id].keys())
            del self.chunks[chunk_id], self._vectors[chunk_id], self._tf[chunk_id]
        self._df += Counter()
        return True

    def manifest(self) -> Manifest:
        return Manifest(EMBEDDING_MODEL, self.chunking.name, {d: doc.sha256 for d, doc in sorted(self.documents.items())})

    # -- retrieval ---------------------------------------------------------------------------------
    def visible(self, doc_id: str, roles: frozenset[str], tenant: str) -> bool:
        document = self.documents.get(doc_id)
        if document is None or document.quarantined:
            return False
        if document.tenant not in ("platform", tenant):
            return False
        return "public" in document.acl_roles or bool(roles & set(document.acl_roles))

    def search(self, query: str, roles: frozenset[str], tenant: str = "platform", k: int = 5,
               mode: Mode = "hybrid", tags: list[str] | None = None) -> list[Hit]:
        candidates = [c for c in self.chunks.values() if self.visible(c.doc_id, roles, tenant)
                      and (not tags or set(tags) & set(self.documents[c.doc_id].tags))]
        if not candidates:
            return []
        lexical = sorted(candidates, key=lambda c: -self._bm25(query, c.chunk_id))
        lexical = [c for c in lexical if self._bm25(query, c.chunk_id) > 0]
        query_vector = embed(query)
        vector = sorted(candidates, key=lambda c: -_cosine(query_vector, self._vectors[c.chunk_id]))
        lex_rank = {c.chunk_id: i for i, c in enumerate(lexical)}
        vec_rank = {c.chunk_id: i for i, c in enumerate(vector)}
        scored = []
        for piece in candidates:
            if mode == "lexical":
                if piece.chunk_id not in lex_rank:
                    continue
                score = self._bm25(query, piece.chunk_id)
            elif mode == "vector":
                score = _cosine(query_vector, self._vectors[piece.chunk_id])
            else:
                score = sum(1.0 / (60 + rank[piece.chunk_id] + 1) for rank in (lex_rank, vec_rank) if piece.chunk_id in rank)
            scored.append(Hit(piece, score, lex_rank.get(piece.chunk_id), vec_rank.get(piece.chunk_id)))
        scored.sort(key=lambda h: (-h.score, h.chunk.chunk_id))
        return scored[:k]

    def _bm25(self, query: str, chunk_id: str, k1: float = 1.2, b: float = 0.75) -> float:
        terms = self._tf[chunk_id]
        length = sum(terms.values()) or 1
        average = (sum(sum(t.values()) for t in self._tf.values()) / len(self._tf)) if self._tf else 1
        n = len(self._tf)
        score = 0.0
        for term in set(tokens(query)):
            frequency = terms.get(term, 0)
            if not frequency:
                continue
            idf = math.log(1 + (n - self._df[term] + 0.5) / (self._df[term] + 0.5))
            score += idf * frequency * (k1 + 1) / (frequency + k1 * (1 - b + b * length / average))
        return score

    # -- persistence -------------------------------------------------------------------------------
    def save(self, path: Path) -> None:
        data = {"manifest": asdict(self.manifest()), "documents": [asdict(d) for d in self.documents.values()]}
        path.write_text(json.dumps(data, indent=1, sort_keys=True), encoding="utf-8")

    @classmethod
    def load(cls, path: Path) -> InMemoryIndex:
        data = json.loads(path.read_text(encoding="utf-8"))
        manifest = data["manifest"]
        if manifest["embedding_model"] != EMBEDDING_MODEL:
            raise ValueError("Index built with a different embedding model; re-index required")
        index = cls(CHUNKING_CONFIGS[manifest["chunking"]])
        for raw in data["documents"]:
            index.upsert(Document(**raw))
        return index


def build_from_corpus(corpus: Path, chunking: str) -> tuple[InMemoryIndex, dict[str, str]]:
    index = InMemoryIndex(CHUNKING_CONFIGS[chunking])
    outcomes = {}
    seen_hashes: dict[str, str] = {}
    for path in sorted(corpus.glob("*.md")):
        document = parse(path)
        if document.sha256 in seen_hashes:
            outcomes[document.doc_id] = f"duplicate-of:{seen_hashes[document.sha256]}"
            continue
        seen_hashes[document.sha256] = document.doc_id
        outcome = index.upsert(document)
        outcomes[document.doc_id] = "quarantined" if document.quarantined else outcome
    return index, outcomes


def _cosine(a: list[float], b: list[float]) -> float:
    return sum(x * y for x, y in zip(a, b, strict=True))
