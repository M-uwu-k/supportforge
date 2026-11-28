# Retrieval benchmark

[Home](../README.md) · [简体中文](benchmark.zh-CN.md) · [Architecture](architecture.md)

## C-MTEB EcomRetrieval

The run recorded on 2026-11-27 used 100,902 passages and 1,000 Chinese queries, each with one relevant passage. Candidates were capped at 100 and the final ranking at 10. The run used real 1,024-dimensional embeddings, Qdrant and the `qwen3.7-text-rerank` reranker. The embedding model ID in the report is `qwen3.7-text-embedding`. The benchmark collection is separate from the customer knowledge index.

| Input | Pinned revision |
| --- | --- |
| [Corpus and queries](https://huggingface.co/datasets/C-MTEB/EcomRetrieval) | `687de13dc7294d6fd9be10c6945f9e8fec8166b9` |
| [Relevance labels](https://huggingface.co/datasets/C-MTEB/EcomRetrieval-qrels) | `39c90699b034ec22ac45b3abf5b0bbb5ffd421f9` |

| Variant | Candidate Recall@100 | Recall@10 | MRR@10 | nDCG@10 | Candidate misses | Ranking misses | P95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dense-only | 95.80% | **85.10%** | **63.50%** | **68.73%** | 42 | 107 | 173.21 ms |
| BM25-only | 80.20% | 60.10% | 41.94% | 46.28% | 198 | 201 | 168.49 ms |
| Hybrid RRF | 95.40% | 76.20% | 53.72% | 59.07% | 46 | 192 | 170.82 ms |
| Hybrid + reranker | 95.40% | 83.80% | 62.33% | 67.47% | 46 | 116 | 1,043.32 ms |

A **candidate miss** means the relevant passage was outside the candidate pool. A **ranking miss** means it reached the pool but fell outside the final top 10. Reranking left candidate misses at 46 and cut ranking misses from 192 to 116. Dense-only still had the best Recall@10 in this run.

P95 summarizes per-query retrieval latency from a sequential run on one machine. Chat generation is outside the measurement. The table reports SupportForge's own run on the public dataset.

## Reproduce on the enterprise stack

Start the [enterprise profile](../README.md#enterprise-deployment) with real providers. In the current PowerShell session, load the local admin and auditor tokens into `$adminToken` and `$auditorToken` respectively. Dataset import calls the embedding service.

```powershell
python -m venv .venv-benchmark
.\.venv-benchmark\Scripts\python.exe -m pip install -r scripts\requirements-ecom-benchmark.txt

.\scripts\Prepare-EcomRetrieval.ps1 -PythonCommand '.\.venv-benchmark\Scripts\python.exe'
.\scripts\Import-EcomRetrieval.ps1 -BearerToken $adminToken
.\scripts\Run-PublicRetrievalBenchmark.ps1 -BearerToken $auditorToken -CandidateK 100 -K 10
```

The import resumes from its checkpoint. The benchmark runner requires real providers and writes JSON and Markdown reports to `data/benchmark-reports/`. The [service implementation](../src/main/java/com/supportforge/publicbenchmark/PublicRetrievalBenchmarkService.java) contains the four variants and ranking metrics.
