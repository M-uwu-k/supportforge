package com.supportforge.vector;

import com.supportforge.config.QdrantProperties;
import com.supportforge.retrieval.HybridRetrievalPort;
import com.supportforge.retrieval.HybridSearchHit;
import com.supportforge.retrieval.HybridSearchRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** local/test向量索引，实现同样的维度和版本校验但不连接Qdrant。 */
@Component
@ConditionalOnProperty(
        prefix = "supportforge.qdrant",
        name = "provider",
        havingValue = "memory",
        matchIfMissing = true
)
public class InMemoryVectorIndexAdapter
        implements VectorIndexPort, HybridRetrievalPort {

    private static final Pattern TERM = Pattern.compile("[\\p{L}\\p{N}_-]+");

    private final QdrantProperties properties;
    private final Map<UUID, KnowledgeVectorPoint> points =
            new ConcurrentHashMap<>();

    public InMemoryVectorIndexAdapter(QdrantProperties properties) {
        this.properties = properties;
    }

    @Override
    public QdrantCollectionSpec ensureReady() {
        return new QdrantCollectionSpec(
                properties.collection(),
                properties.denseVectorName(),
                properties.dimensions(),
                properties.distance(),
                properties.indexVersion()
        );
    }

    @Override
    public void upsert(List<KnowledgeVectorPoint> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("待写入向量Point不能为空");
        }
        for (KnowledgeVectorPoint point : requested) {
            validate(point);
            points.put(point.pointId(), point);
        }
    }

    public Map<UUID, KnowledgeVectorPoint> snapshot() {
        return Map.copyOf(points);
    }

    @Override
    public List<HybridSearchHit> search(HybridSearchRequest request) {
        if (request.denseVector().size() != properties.dimensions()) {
            throw new IllegalArgumentException("查询向量维度与Collection不一致");
        }
        if (!request.indexVersion().equals(properties.indexVersion())) {
            throw new IllegalArgumentException("查询索引版本与Collection不一致");
        }
        List<KnowledgeVectorPoint> eligible = points.values().stream()
                .filter(point -> point.active()
                        && !request.excludedPointIds().contains(point.pointId())
                        && point.tenantId().equals(request.tenantId())
                        && point.indexVersion().equals(request.indexVersion())
                        && categoryMatches(point.category(), request.categories()))
                .toList();

        List<KnowledgeVectorPoint> dense = eligible.stream()
                .sorted(Comparator.comparingDouble(
                        (KnowledgeVectorPoint point) -> cosine(
                                request.denseVector(), point.denseVector()
                        )
                ).reversed())
                .limit(request.candidateLimit())
                .toList();

        Map<String, Integer> queryTerms = termFrequency(request.queryText());
        List<KnowledgeVectorPoint> lexical = eligible.stream()
                .filter(point -> lexicalScore(queryTerms, point.content()) > 0.0)
                .sorted(Comparator.comparingDouble(
                        (KnowledgeVectorPoint point) -> lexicalScore(
                                queryTerms, point.content()
                        )
                ).reversed())
                .limit(request.candidateLimit())
                .toList();

        Map<UUID, Double> scores = new HashMap<>();
        fuse(scores, dense, request.rrfK());
        fuse(scores, lexical, request.rrfK());
        Map<UUID, KnowledgeVectorPoint> byId = new LinkedHashMap<>();
        eligible.forEach(point -> byId.put(point.pointId(), point));
        return scores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().toString()))
                .limit(request.limit())
                .map(entry -> toHit(byId.get(entry.getKey()), entry.getValue()))
                .toList();
    }

    private static void fuse(
            Map<UUID, Double> scores,
            List<KnowledgeVectorPoint> ranked,
            int k
    ) {
        for (int rank = 0; rank < ranked.size(); rank++) {
            scores.merge(
                    ranked.get(rank).pointId(),
                    1.0 / (k + rank),
                    Double::sum
            );
        }
    }

    private static HybridSearchHit toHit(
            KnowledgeVectorPoint point,
            double score
    ) {
        return new HybridSearchHit(
                point.pointId(), point.documentId(), point.documentVersionId(),
                point.externalDocumentId(), point.chunkIndex(), point.content(),
                point.contentSha256(), point.category(), point.indexVersion(), score
        );
    }

    private static boolean categoryMatches(String category, Set<String> expected) {
        return expected.isEmpty() || expected.contains(category);
    }

    private static double cosine(List<Float> left, List<Float> right) {
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < left.size(); index++) {
            double leftValue = left.get(index);
            double rightValue = right.get(index);
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / Math.sqrt(leftNorm * rightNorm);
    }

    private static double lexicalScore(
            Map<String, Integer> queryTerms,
            String document
    ) {
        Map<String, Integer> documentTerms = termFrequency(document);
        double score = 0.0;
        for (Map.Entry<String, Integer> query : queryTerms.entrySet()) {
            int frequency = documentTerms.getOrDefault(query.getKey(), 0);
            if (frequency > 0) {
                score += query.getValue() * (1.0 + Math.log(frequency));
            }
        }
        return score / Math.sqrt(Math.max(1, documentTerms.size()));
    }

    private static Map<String, Integer> termFrequency(String text) {
        Map<String, Integer> terms = new HashMap<>();
        Matcher matcher = TERM.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            terms.merge(token, 1, Integer::sum);
            List<String> han = new ArrayList<>();
            token.codePoints()
                    .filter(codePoint -> Character.UnicodeScript.of(codePoint)
                            == Character.UnicodeScript.HAN)
                    .forEach(codePoint -> han.add(
                            new String(Character.toChars(codePoint))
                    ));
            for (String character : han) {
                terms.merge(character, 1, Integer::sum);
            }
            for (int index = 0; index + 1 < han.size(); index++) {
                terms.merge(han.get(index) + han.get(index + 1), 1, Integer::sum);
            }
        }
        return terms;
    }

    private void validate(KnowledgeVectorPoint point) {
        if (point.denseVector().size() != properties.dimensions()) {
            throw new IllegalArgumentException("向量Point维度与Collection不一致");
        }
        if (!point.indexVersion().equals(properties.indexVersion())) {
            throw new IllegalArgumentException("向量Point索引版本与Collection不一致");
        }
    }
}
