"""Document parsing, provenance, chunking and injection screening."""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, field
from pathlib import Path

import yaml

from fixai_common import guard

TOKEN = re.compile(r"[A-Za-z0-9]+(?:\([0-9]+\))?")


@dataclass(frozen=True)
class ChunkingConfig:
    """Chunking parameters are part of the index version and are benchmarked (see evals rag-quality suite)."""

    name: str
    max_words: int
    overlap_words: int
    split_on_headings: bool = True


CHUNKING_CONFIGS = {
    "heading-w120-o20": ChunkingConfig("heading-w120-o20", 120, 20, True),
    "heading-w60-o10": ChunkingConfig("heading-w60-o10", 60, 10, True),
    "fixed-w200-o0": ChunkingConfig("fixed-w200-o0", 200, 0, False),
}
DEFAULT_CHUNKING = "heading-w120-o20"


@dataclass(frozen=True)
class Chunk:
    chunk_id: str
    doc_id: str
    title: str
    section: str
    text: str
    start: int
    end: int


@dataclass
class Document:
    doc_id: str
    title: str
    version: int
    tags: list[str]
    acl_roles: list[str]
    tenant: str
    source: str
    body: str
    sha256: str
    injection_flags: list[str] = field(default_factory=list)

    @property
    def quarantined(self) -> bool:
        """Documents with prompt-injection indicators are excluded from retrieval until reviewed."""
        return bool(self.injection_flags)


def parse(path: Path) -> Document:
    text = path.read_text(encoding="utf-8")
    if not text.startswith("---\n"):
        raise ValueError(f"{path.name}: missing front matter")
    _, front, body = text.split("---\n", 2)
    meta = yaml.safe_load(front) or {}
    for key in ("doc_id", "title", "version"):
        if key not in meta:
            raise ValueError(f"{path.name}: front matter missing {key}")
    body = guard.sanitize(body, 200_000)
    return Document(doc_id=str(meta["doc_id"]), title=str(meta["title"]), version=int(meta["version"]),
                    tags=[str(t) for t in meta.get("tags", [])], acl_roles=[str(r) for r in meta.get("acl_roles", ["public"])],
                    tenant=str(meta.get("tenant", "platform")), source=path.name, body=body,
                    sha256=hashlib.sha256(body.encode()).hexdigest(),
                    injection_flags=sorted({f.pattern for f in guard.scan(body)}))


def chunk(document: Document, config: ChunkingConfig) -> list[Chunk]:
    sections: list[tuple[str, int, int]] = []
    if config.split_on_headings:
        headings = [m for m in re.finditer(r"^#+ (.+)$", document.body, re.M)]
        if not headings:
            sections.append((document.title, 0, len(document.body)))
        for index, heading in enumerate(headings):
            end = headings[index + 1].start() if index + 1 < len(headings) else len(document.body)
            sections.append((heading.group(1).strip(), heading.end(), end))
    else:
        sections.append((document.title, 0, len(document.body)))

    chunks: list[Chunk] = []
    for section, start, end in sections:
        words = [(m.start() + start, m.end() + start) for m in re.finditer(r"\S+", document.body[start:end])]
        if not words:
            continue
        step = max(1, config.max_words - config.overlap_words)
        for offset in range(0, len(words), step):
            window = words[offset:offset + config.max_words]
            span_start, span_end = window[0][0], window[-1][1]
            index = len(chunks)
            chunks.append(Chunk(chunk_id=f"{document.doc_id}#v{document.version}#{config.name}#{index}",
                                doc_id=document.doc_id, title=document.title, section=section,
                                text=document.body[span_start:span_end], start=span_start, end=span_end))
            if offset + config.max_words >= len(words):
                break
    return chunks


def tokens(text: str) -> list[str]:
    return [t.lower() for t in TOKEN.findall(text)]
