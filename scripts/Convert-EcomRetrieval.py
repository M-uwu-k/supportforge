#!/usr/bin/env python3
"""把固定revision的C-MTEB EcomRetrieval Parquet转换成流式JSONL。"""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd


DATASET_ID = "c-mteb-ecom-retrieval"
DATASET_REVISION = (
    "687de13dc7294d6fd9be10c6945f9e8fec8166b9+"
    "39c90699b034ec22ac45b3abf5b0bbb5ffd421f9"
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def require_columns(frame: pd.DataFrame, expected: set[str], label: str) -> None:
    actual = set(frame.columns)
    if actual != expected:
        raise ValueError(f"{label}列不匹配: expected={expected}, actual={actual}")


def write_jsonl(path: Path, rows) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")))
            handle.write("\n")
    temporary.replace(path)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    raw_dir: Path = args.raw_dir.resolve()
    output_dir: Path = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    corpus_path = raw_dir / "corpus.parquet"
    queries_path = raw_dir / "queries.parquet"
    qrels_path = raw_dir / "qrels.parquet"
    for path in (corpus_path, queries_path, qrels_path):
        if not path.is_file():
            raise FileNotFoundError(path)

    corpus = pd.read_parquet(corpus_path)
    queries = pd.read_parquet(queries_path)
    qrels = pd.read_parquet(qrels_path)
    require_columns(corpus, {"id", "text"}, "corpus")
    require_columns(queries, {"id", "text"}, "queries")
    require_columns(qrels, {"qid", "pid", "score"}, "qrels")

    corpus["id"] = corpus["id"].astype(str)
    queries["id"] = queries["id"].astype(str)
    qrels["qid"] = qrels["qid"].astype(str)
    qrels["pid"] = qrels["pid"].astype(str)
    if corpus["id"].duplicated().any() or queries["id"].duplicated().any():
        raise ValueError("corpus或queries含重复id")
    if corpus["text"].isna().any() or queries["text"].isna().any():
        raise ValueError("corpus或queries含空text")
    if (qrels["score"] < 1).any():
        raise ValueError("qrels含非正相关度")

    corpus_ids = set(corpus["id"])
    query_ids = set(queries["id"])
    if not set(qrels["pid"]).issubset(corpus_ids):
        raise ValueError("qrels引用不存在的corpus id")
    if not set(qrels["qid"]).issubset(query_ids):
        raise ValueError("qrels引用不存在的query id")

    grouped: dict[str, dict[str, int]] = {}
    for row in qrels.itertuples(index=False):
        judgments = grouped.setdefault(str(row.qid), {})
        pid = str(row.pid)
        score = int(row.score)
        judgments[pid] = max(score, judgments.get(pid, 0))
    if set(grouped) != query_ids:
        missing = sorted(query_ids - set(grouped))[:10]
        raise ValueError(f"存在没有qrels的query: {missing}")

    write_jsonl(
        output_dir / "corpus.jsonl",
        (
            {"id": str(row.id), "text": str(row.text).strip()}
            for row in corpus.itertuples(index=False)
        ),
    )
    write_jsonl(
        output_dir / "queries.jsonl",
        (
            {
                "id": str(row.id),
                "text": str(row.text).strip(),
                "relevance_judgments": grouped[str(row.id)],
            }
            for row in queries.itertuples(index=False)
        ),
    )

    manifest = {
        "dataset_id": DATASET_ID,
        "dataset_revision": DATASET_REVISION,
        "source": {
            "corpus_queries": "C-MTEB/EcomRetrieval@687de13dc7294d6fd9be10c6945f9e8fec8166b9",
            "qrels": "C-MTEB/EcomRetrieval-qrels@39c90699b034ec22ac45b3abf5b0bbb5ffd421f9",
        },
        "raw_sha256": {
            "corpus.parquet": sha256(corpus_path),
            "queries.parquet": sha256(queries_path),
            "qrels.parquet": sha256(qrels_path),
        },
        "corpus_count": int(len(corpus)),
        "query_count": int(len(queries)),
        "qrels_count": int(len(qrels)),
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "redistribution_notice": (
            "Dataset card does not state a complete redistribution license; "
            "do not commit raw or converted data to the public repository."
        ),
    }
    manifest_path = output_dir / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        f"converted corpus={len(corpus)} queries={len(queries)} "
        f"qrels={len(qrels)} output={output_dir}"
    )


if __name__ == "__main__":
    main()

