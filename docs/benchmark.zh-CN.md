# 检索评测

[项目首页](../README.zh-CN.md) · [English](benchmark.md) · [系统架构](architecture.zh-CN.md)

## C-MTEB EcomRetrieval

2026-11-27 记录的评测使用 100,902 篇语料和 1,000 条中文查询，每条查询有一篇相关文档。候选池最多取 100 条，最终排序保留前 10 条。运行使用真实 1,024 维 Embedding、Qdrant 和 Reranker，报告中的模型标识为 `qwen3.7-text-embedding` 和 `qwen3.7-text-rerank`。公开评测使用独立的 Qdrant Collection。

| 输入 | 固定版本 |
| --- | --- |
| [语料和查询](https://huggingface.co/datasets/C-MTEB/EcomRetrieval) | `687de13dc7294d6fd9be10c6945f9e8fec8166b9` |
| [相关性标注](https://huggingface.co/datasets/C-MTEB/EcomRetrieval-qrels) | `39c90699b034ec22ac45b3abf5b0bbb5ffd421f9` |

| 方案 | 候选 Recall@100 | Recall@10 | MRR@10 | nDCG@10 | 候选未召回 | 排序未进前十 | P95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dense-only | 95.80% | **85.10%** | **63.50%** | **68.73%** | 42 | 107 | 173.21 ms |
| BM25-only | 80.20% | 60.10% | 41.94% | 46.28% | 198 | 201 | 168.49 ms |
| Hybrid RRF | 95.40% | 76.20% | 53.72% | 59.07% | 46 | 192 | 170.82 ms |
| Hybrid + reranker | 95.40% | 83.80% | 62.33% | 67.47% | 46 | 116 | 1,043.32 ms |

**候选未召回**表示相关文档未进入候选池。**排序未进前十**表示相关文档进入了候选池，但未进入最终 Top 10。加入精排后，候选未召回仍为 46 条，排序未进前十从 192 条降至 116 条。这组数据下 Dense-only 的 Recall@10 最高。

P95 是单机顺序运行时逐查询检索耗时的第 95 百分位。表中测量范围为 SupportForge 在该公开数据集上的检索阶段。

## 在企业版上复跑

启动已配置真实供应商的[企业版](../README.zh-CN.md#启动企业版)。在当前 PowerShell 会话中，把本地管理员和审计员令牌分别读入 `$adminToken` 与 `$auditorToken`。导入语料时会调用 Embedding 服务。

```powershell
python -m venv .venv-benchmark
.\.venv-benchmark\Scripts\python.exe -m pip install -r scripts\requirements-ecom-benchmark.txt

.\scripts\Prepare-EcomRetrieval.ps1 -PythonCommand '.\.venv-benchmark\Scripts\python.exe'
.\scripts\Import-EcomRetrieval.ps1 -BearerToken $adminToken
.\scripts\Run-PublicRetrievalBenchmark.ps1 -BearerToken $auditorToken -CandidateK 100 -K 10
```

导入脚本支持断点续跑。评测脚本要求真实供应商，结果以 JSON 和 Markdown 写入 `data/benchmark-reports/`。四种检索方案和排序指标见 [Java 评测实现](../src/main/java/com/supportforge/publicbenchmark/PublicRetrievalBenchmarkService.java)。
