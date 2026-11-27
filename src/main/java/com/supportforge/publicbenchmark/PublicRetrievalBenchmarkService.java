package com.supportforge.publicbenchmark;

import com.supportforge.benchmark.RetrievalBenchmarkEnvironment;
import com.supportforge.benchmark.RetrievalBenchmarkEnvironmentException;
import com.supportforge.benchmark.RetrievalBenchmarkVariant;
import com.supportforge.benchmark.RetrievalMissType;
import com.supportforge.config.EmbeddingProperties;
import com.supportforge.config.FeatureFlagProperties;
import com.supportforge.config.QdrantProperties;
import com.supportforge.config.RerankerProperties;
import com.supportforge.embedding.EmbeddingBatch;
import com.supportforge.embedding.EmbeddingProvider;
import com.supportforge.reranker.RerankBatch;
import com.supportforge.reranker.RerankScore;
import com.supportforge.reranker.RerankerProvider;
import com.supportforge.retrieval.HybridSearchHit;
import com.supportforge.retrieval.HybridSearchRequest;
import com.supportforge.vector.KnowledgeVectorPoint;
import com.supportforge.vector.QdrantVectorIndexAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 隔离公开语料的Java评测引擎。复用真实Embedding、Qdrant和Reranker，
 * 但不写生产PostgreSQL知识表，也不经过客服文档分块。
 */
@Service
@ConditionalOnProperty(
        prefix = "supportforge.qdrant",
        name = "provider",
        havingValue = "qdrant"
)
public class PublicRetrievalBenchmarkService {
    private static final String CATEGORY = "PUBLIC_ECOM_BENCHMARK";
    private static final int TRANSIENT_MAX_ATTEMPTS = 5;
    private static final Logger LOGGER = LoggerFactory.getLogger(
            PublicRetrievalBenchmarkService.class
    );

    private final PublicBenchmarkProperties properties;
    private final EmbeddingProvider embeddingProvider;
    private final EmbeddingProperties embedding;
    private final QdrantProperties qdrant;
    private final RerankerProvider rerankerProvider;
    private final RerankerProperties reranker;
    private final QdrantVectorIndexAdapter vectorStore;

    public PublicRetrievalBenchmarkService(
            PublicBenchmarkProperties properties,
            EmbeddingProvider embeddingProvider,
            EmbeddingProperties embedding,
            QdrantProperties qdrant,
            RerankerProvider rerankerProvider,
            RerankerProperties reranker,
            FeatureFlagProperties featureFlags,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.embeddingProvider = embeddingProvider;
        this.embedding = embedding;
        this.qdrant = qdrant;
        this.rerankerProvider = rerankerProvider;
        this.reranker = reranker;
        QdrantProperties isolated = new QdrantProperties(
                "qdrant", qdrant.endpoint(), qdrant.apiKey(),
                properties.collection(), qdrant.denseVectorName(),
                properties.indexVersion(), qdrant.dimensions(), qdrant.distance(),
                qdrant.shardNumber(), qdrant.replicationFactor(),
                qdrant.writeConsistencyFactor(), qdrant.upsertBatchSize(),
                qdrant.timeout()
        );
        this.vectorStore = new QdrantVectorIndexAdapter(
                isolated, embedding, featureFlags, objectMapper
        );
    }

    public PublicBenchmarkStatus ensureReady() {
        vectorStore.ensureReady();
        return status();
    }

    public PublicBenchmarkStatus status() {
        long points = vectorStore.countPoints(
                properties.tenantId(), properties.indexVersion()
        );
        return new PublicBenchmarkStatus(
                properties.datasetId(), properties.datasetRevision(),
                properties.collection(), properties.indexVersion(), points,
                embedding.model(), embedding.modelVersion(), embedding.dimensions()
        );
    }

    public PublicBenchmarkImportResult importBatch(
            PublicBenchmarkImportRequest request
    ) {
        requireIdentity(request.datasetId(), request.datasetRevision());
        if (request.passages().size() > properties.maximumImportBatch()) {
            throw new IllegalArgumentException(
                    "单批passage不能超过" + properties.maximumImportBatch()
            );
        }
        vectorStore.ensureReady();
        List<String> texts = request.passages().stream()
                .map(PublicBenchmarkPassage::text).toList();
        EmbeddingBatch batch = withTransientRetry(
                "public corpus embedding",
                () -> embeddingProvider.embedDocuments(texts)
        );
        if (batch.vectors().size() != request.passages().size()
                || batch.dimensions() != embedding.dimensions()) {
            throw new IllegalStateException("公共语料Embedding数量或维度不一致");
        }
        List<KnowledgeVectorPoint> points = new ArrayList<>();
        for (int index = 0; index < request.passages().size(); index++) {
            PublicBenchmarkPassage passage = request.passages().get(index);
            UUID documentId = stableUuid("document", passage.id());
            points.add(new KnowledgeVectorPoint(
                    stableUuid("point", passage.id()), properties.tenantId(),
                    documentId, stableUuid("version", passage.id()),
                    passage.id(), 0, passage.text(), sha256(passage.text()),
                    CATEGORY, properties.indexVersion(), embedding.model(),
                    embedding.modelVersion(), batch.vectors().get(index).values(), true
            ));
        }
        withTransientRetry("public corpus upsert", () -> {
            vectorStore.upsert(points);
            return null;
        });
        PublicBenchmarkPassage first = request.passages().getFirst();
        PublicBenchmarkPassage last = request.passages().getLast();
        return new PublicBenchmarkImportResult(
                request.passages().size(), points.size(), first.id(), last.id(),
                properties.collection(), properties.indexVersion(),
                vectorStore.countPoints(properties.tenantId(), properties.indexVersion())
        );
    }

    public PublicBenchmarkReport evaluate(PublicBenchmarkRunRequest request) {
        requireIdentity(request.datasetId(), request.datasetRevision());
        if (request.queries().size() > properties.maximumQueries()) {
            throw new IllegalArgumentException(
                    "公共Benchmark查询数不能超过" + properties.maximumQueries()
            );
        }
        if (request.k() > properties.finalLimit()
                || request.candidateK() > properties.candidateLimit()) {
            throw new IllegalArgumentException(
                    "公共Benchmark请求超过配置上限：k<="
                            + properties.finalLimit() + ", candidate_k<="
                            + properties.candidateLimit()
            );
        }
        RetrievalBenchmarkEnvironment environment = environment();
        if (request.requiresRealProviders() && !environment.realProviders()) {
            throw new RetrievalBenchmarkEnvironmentException(
                    "正式公共Benchmark要求Qdrant、真实Embedding和真实Reranker"
            );
        }
        long indexedPoints = status().indexedPoints();
        if (indexedPoints == 0) {
            throw new IllegalStateException("公共Benchmark Collection尚未导入语料");
        }

        Map<RetrievalBenchmarkVariant, VariantAccumulator> accumulators =
                new EnumMap<>(RetrievalBenchmarkVariant.class);
        for (RetrievalBenchmarkVariant variant : RetrievalBenchmarkVariant.values()) {
            accumulators.put(variant, new VariantAccumulator(
                    variant, request.candidateK(), request.k()
            ));
        }

        int completedQueries = 0;
        for (PublicBenchmarkQuery query : request.queries()) {
            long embeddingStarted = System.nanoTime();
            EmbeddingBatch embedded = withTransientRetry(
                    "public benchmark query embedding",
                    () -> embeddingProvider.embedQuery(query.text())
            );
            long embeddingNanos = Math.max(0L, System.nanoTime() - embeddingStarted);
            List<Float> vector = embedded.vectors().getFirst().values();
            for (RetrievalBenchmarkVariant variant : RetrievalBenchmarkVariant.values()) {
                long searchStarted = System.nanoTime();
                HybridSearchRequest searchRequest = new HybridSearchRequest(
                                properties.tenantId(), query.text(), vector, Set.of(),
                                properties.indexVersion(), request.candidateK(),
                                request.candidateK(), properties.rrfK(), Set.of(),
                                variant.retrievalMode()
                        );
                List<HybridSearchHit> candidates = withTransientRetry(
                        "public benchmark Qdrant search",
                        () -> vectorStore.search(searchRequest)
                );
                List<RankedPassage> finalResults = variant.usesReranker()
                        ? rerank(query.text(), candidates, request.k())
                        : truncate(candidates, request.k());
                long elapsed = embeddingNanos
                        + Math.max(0L, System.nanoTime() - searchStarted);
                accumulators.get(variant).record(
                        query, candidates, finalResults, elapsed,
                        variant.usesReranker() && !candidates.isEmpty()
                );
            }
            completedQueries++;
            if (completedQueries % 50 == 0
                    || completedQueries == request.queries().size()) {
                LOGGER.info(
                        "public_benchmark_progress completed={} total={}",
                        completedQueries,
                        request.queries().size()
                );
            }
        }

        List<PublicBenchmarkVariantReport> reports = new ArrayList<>();
        for (RetrievalBenchmarkVariant variant : RetrievalBenchmarkVariant.values()) {
            reports.add(accumulators.get(variant).report());
        }
        return new PublicBenchmarkReport(
                request.datasetId(), request.datasetRevision(), environment,
                properties.collection(), properties.indexVersion(), indexedPoints,
                request.k(), request.candidateK(), reports, Instant.now()
        );
    }

    private List<RankedPassage> rerank(
            String query,
            List<HybridSearchHit> candidates,
            int limit
    ) {
        if (candidates.isEmpty()) return List.of();
        RerankBatch batch = withTransientRetry(
                "public benchmark reranker",
                () -> rerankerProvider.rerank(
                        query,
                        candidates.stream().map(HybridSearchHit::content).toList()
                )
        );
        Map<Integer, Double> scores = new HashMap<>();
        for (RerankScore score : batch.scores()) {
            if (scores.put(score.index(), score.score()) != null) {
                throw new IllegalStateException("Reranker返回重复候选序号");
            }
        }
        if (scores.size() != candidates.size()) {
            throw new IllegalStateException("Reranker未覆盖公共Benchmark全部候选");
        }
        List<RankedPassage> ranked = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            Double score = scores.get(index);
            if (score == null) {
                throw new IllegalStateException("Reranker缺少公共Benchmark候选序号");
            }
            ranked.add(new RankedPassage(candidates.get(index), score));
        }
        ranked.sort(Comparator
                .comparingDouble(RankedPassage::score).reversed()
                .thenComparing(Comparator.comparingDouble(
                        (RankedPassage value) -> value.hit().score()
                ).reversed())
                .thenComparing(value -> value.hit().pointId().toString()));
        return List.copyOf(ranked.subList(0, Math.min(limit, ranked.size())));
    }

    private static List<RankedPassage> truncate(
            List<HybridSearchHit> candidates,
            int limit
    ) {
        List<RankedPassage> results = new ArrayList<>();
        for (int index = 0; index < Math.min(limit, candidates.size()); index++) {
            HybridSearchHit hit = candidates.get(index);
            results.add(new RankedPassage(hit, hit.score()));
        }
        return List.copyOf(results);
    }

    private void requireIdentity(String datasetId, String revision) {
        if (!properties.datasetId().equals(datasetId)
                || !properties.datasetRevision().equals(revision)) {
            throw new IllegalArgumentException(
                    "公共数据集身份不匹配：期望" + properties.datasetId()
                            + "@" + properties.datasetRevision()
            );
        }
    }

    private RetrievalBenchmarkEnvironment environment() {
        boolean real = qdrant.provider().equals("qdrant")
                && embedding.provider().equals("openai-compatible")
                && reranker.provider().equals("vllm");
        return new RetrievalBenchmarkEnvironment(
                qdrant.provider(), properties.collection(), properties.indexVersion(),
                embedding.provider(), embedding.model(), embedding.modelVersion(),
                reranker.provider(), reranker.model(), reranker.modelVersion(), real
        );
    }

    private <T> T withTransientRetry(
            String operation,
            Supplier<T> action
    ) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= TRANSIENT_MAX_ATTEMPTS; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException exception) {
                if (!isTransient(exception)
                        || attempt == TRANSIENT_MAX_ATTEMPTS) {
                    throw exception;
                }
                lastFailure = exception;
                long delayMillis = 250L << (attempt - 1);
                LOGGER.warn(
                        "public_benchmark_transient_failure operation={} attempt={}/{} retry_delay_ms={}",
                        operation,
                        attempt,
                        TRANSIENT_MAX_ATTEMPTS,
                        delayMillis
                );
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            operation + " retry interrupted",
                            interrupted
                    );
                }
            }
        }
        throw new IllegalStateException(
                operation + " failed after retries",
                lastFailure
        );
    }

    private static boolean isTransient(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof IOException) return true;
            String message = current.getMessage();
            if (message != null && message.matches(".*HTTP 5\\d\\d.*")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private UUID stableUuid(String type, String externalId) {
        return UUID.nameUUIDFromBytes((
                properties.datasetId() + "|" + properties.datasetRevision()
                        + "|" + type + "|" + externalId
        ).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM缺少SHA-256", exception);
        }
    }

    private static double percentileMillis(List<Long> values, double percentile) {
        if (values.isEmpty()) return 0.0;
        List<Long> sorted = values.stream().sorted().toList();
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(index) / 1_000_000.0;
    }

    private record RankedPassage(HybridSearchHit hit, double score) { }

    private static final class VariantAccumulator {
        private final RetrievalBenchmarkVariant variant;
        private final int candidateK;
        private final int k;
        private final List<PublicBenchmarkCaseResult> details = new ArrayList<>();
        private final List<Long> latencies = new ArrayList<>();
        private double candidateHitSum;
        private double candidateRecallSum;
        private double hitSum;
        private double recallSum;
        private double reciprocalRankSum;
        private double ndcgSum;
        private double averagePrecisionSum;
        private int successful;
        private int candidateMisses;
        private int rankingMisses;

        private VariantAccumulator(
                RetrievalBenchmarkVariant variant,
                int candidateK,
                int k
        ) {
            this.variant = variant;
            this.candidateK = candidateK;
            this.k = k;
        }

        private void record(
                PublicBenchmarkQuery query,
                List<HybridSearchHit> candidates,
                List<RankedPassage> finalResults,
                long elapsed,
                boolean rerankerApplied
        ) {
            List<String> candidateIds = candidates.stream()
                    .map(HybridSearchHit::externalDocumentId).toList();
            List<String> finalIds = finalResults.stream()
                    .map(value -> value.hit().externalDocumentId()).toList();
            Set<String> relevant = query.relevanceJudgments().keySet();
            int firstCandidate = firstRelevant(candidateIds, relevant);
            int firstFinal = firstRelevant(finalIds, relevant);
            double candidateRecall = recall(candidateIds, relevant);
            double finalRecall = recall(finalIds, relevant);
            double rr = firstFinal == 0 ? 0.0 : 1.0 / firstFinal;
            double ndcg = ndcg(finalIds, query.relevanceJudgments(), k);
            double ap = averagePrecision(finalIds, relevant, k);
            RetrievalMissType missType;
            if (firstCandidate == 0) {
                missType = RetrievalMissType.CANDIDATE_MISS;
                candidateMisses++;
            } else if (firstFinal == 0) {
                missType = RetrievalMissType.RANKING_MISS;
                rankingMisses++;
            } else {
                missType = RetrievalMissType.NONE;
                successful++;
            }
            candidateHitSum += firstCandidate == 0 ? 0.0 : 1.0;
            candidateRecallSum += candidateRecall;
            hitSum += firstFinal == 0 ? 0.0 : 1.0;
            recallSum += finalRecall;
            reciprocalRankSum += rr;
            ndcgSum += ndcg;
            averagePrecisionSum += ap;
            latencies.add(elapsed);
            details.add(new PublicBenchmarkCaseResult(
                    query.id(), missType, firstCandidate, firstFinal,
                    candidateRecall, finalRecall, rr, ndcg, ap,
                    elapsed / 1_000_000.0, rerankerApplied
            ));
        }

        private PublicBenchmarkVariantReport report() {
            int count = details.size();
            PublicBenchmarkMetrics metrics = new PublicBenchmarkMetrics(
                    count, candidateK, k,
                    candidateHitSum / count, candidateRecallSum / count,
                    hitSum / count, recallSum / count,
                    reciprocalRankSum / count, ndcgSum / count,
                    averagePrecisionSum / count, successful,
                    candidateMisses, rankingMisses,
                    percentileMillis(latencies, 0.50),
                    percentileMillis(latencies, 0.95),
                    percentileMillis(latencies, 0.99)
            );
            return new PublicBenchmarkVariantReport(
                    variant, metrics, List.of(), details
            );
        }

        private static int firstRelevant(List<String> ranked, Set<String> relevant) {
            for (int index = 0; index < ranked.size(); index++) {
                if (relevant.contains(ranked.get(index))) return index + 1;
            }
            return 0;
        }

        private static double recall(List<String> ranked, Set<String> relevant) {
            Set<String> unique = new HashSet<>(ranked);
            long found = relevant.stream().filter(unique::contains).count();
            return found / (double) relevant.size();
        }

        private static double ndcg(
                List<String> ranked,
                Map<String, Integer> judgments,
                int limit
        ) {
            double dcg = 0.0;
            for (int index = 0; index < Math.min(limit, ranked.size()); index++) {
                int relevance = judgments.getOrDefault(ranked.get(index), 0);
                if (relevance > 0) {
                    dcg += (Math.pow(2.0, relevance) - 1.0)
                            / log2(index + 2.0);
                }
            }
            List<Integer> ideal = judgments.values().stream()
                    .sorted(Comparator.reverseOrder()).limit(limit).toList();
            double idcg = 0.0;
            for (int index = 0; index < ideal.size(); index++) {
                idcg += (Math.pow(2.0, ideal.get(index)) - 1.0)
                        / log2(index + 2.0);
            }
            return idcg == 0.0 ? 0.0 : dcg / idcg;
        }

        private static double averagePrecision(
                List<String> ranked,
                Set<String> relevant,
                int limit
        ) {
            int hits = 0;
            double precisionSum = 0.0;
            Set<String> seen = new LinkedHashSet<>();
            for (int index = 0; index < Math.min(limit, ranked.size()); index++) {
                String id = ranked.get(index);
                if (seen.add(id) && relevant.contains(id)) {
                    hits++;
                    precisionSum += hits / (double) (index + 1);
                }
            }
            return precisionSum / relevant.size();
        }

        private static double log2(double value) {
            return Math.log(value) / Math.log(2.0);
        }
    }
}

