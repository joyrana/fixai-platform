"""Knowledge administration: ingest, delete and re-index. Changes are logged with document hashes for provenance."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from fixai_knowledge_mcp.documents import CHUNKING_CONFIGS, DEFAULT_CHUNKING
from fixai_knowledge_mcp.index import InMemoryIndex, build_from_corpus


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="fixai-knowledge")
    sub = parser.add_subparsers(dest="command", required=True)
    ingest = sub.add_parser("ingest", help="Build an index from a corpus directory")
    ingest.add_argument("--corpus", type=Path, required=True)
    ingest.add_argument("--chunking", choices=sorted(CHUNKING_CONFIGS), default=DEFAULT_CHUNKING)
    ingest.add_argument("--out", type=Path, required=True)
    delete = sub.add_parser("delete", help="Remove a document from an index")
    delete.add_argument("--index", type=Path, required=True)
    delete.add_argument("--doc-id", required=True)
    args = parser.parse_args(argv)

    if args.command == "ingest":
        index, outcomes = build_from_corpus(args.corpus, args.chunking)
        index.save(args.out)
        print(json.dumps({"outcomes": outcomes, "manifest": index.manifest().__dict__, "chunks": len(index.chunks)}, indent=2))
        return 0
    index = InMemoryIndex.load(args.index)
    removed = index.delete(args.doc_id)
    index.save(args.index)
    print(json.dumps({"deleted": removed, "doc_id": args.doc_id}))
    return 0 if removed else 1


if __name__ == "__main__":
    raise SystemExit(main())
