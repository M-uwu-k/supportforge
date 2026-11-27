package com.supportforge.knowledge;

import com.supportforge.objectstorage.StoredObject;
import com.supportforge.persistence.port.KnowledgeDocumentPersistencePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 企业知识以当前租户的数据库记录为准，不共用local模式的全局内存文档列表。 */
@Component
@Primary
@ConditionalOnProperty(prefix = "supportforge.persistence", name = "knowledge", havingValue = "postgres")
public class PostgresKnowledgeBase implements KnowledgeBase {
    private final KnowledgeDocumentPersistencePort persistence;
    private final KnowledgeHybridScorer scorer;
    private com.supportforge.security.ResourceAccessService access;

    @org.springframework.beans.factory.annotation.Autowired
    void configureAccess(com.supportforge.security.ResourceAccessService access) { this.access = access; }

    public PostgresKnowledgeBase(KnowledgeDocumentPersistencePort persistence, KnowledgeHybridScorer scorer) {
        this.persistence = persistence;
        this.scorer = scorer;
    }

    @Override
    public List<KnowledgeDocument> readableDocuments() {

        return persistence.load().stream()
                .filter(document -> access == null || access.allowed("knowledge_document", document.id(),
                        com.supportforge.security.AclPermission.READ))
                .toList();
    }

    @Override
    public List<KnowledgeSearchResult> search(String query, KnowledgeCategory category, int limit) {
        if (query == null || query.isBlank()) return List.of();
        return readableDocuments().stream()
                .filter(document -> category == null || document.category() == category)
                .map(document -> scorer.score(document, query.strip(), category))
                .filter(result -> result.score() > 0)
                .sorted(Comparator.comparingDouble(KnowledgeSearchResult::score).reversed())
                .limit(Math.max(1, Math.min(10, limit))).toList();
    }

    @Override
    public synchronized KnowledgeDocument add(KnowledgeDocument document) {
        validate(document);
        persistence.update(current -> {
            if (current.stream().anyMatch(existing -> existing.id().equals(document.id()))) {
                throw new KnowledgeConflictException("知识文档ID已存在：" + document.id());
            }
            List<KnowledgeDocument> documents = new ArrayList<>(current);
            documents.add(document);
            return documents;
        });
        return document;
    }

    @Override
    public synchronized KnowledgeDocument upsert(KnowledgeDocument document) {
        validate(document);
        persistence.update(current -> {
            List<KnowledgeDocument> documents = new ArrayList<>(current);
            documents.removeIf(item -> item.id().equals(document.id()));
            documents.add(document);
            return documents;
        });
        return document;
    }

    @Override
    public synchronized KnowledgeDocument delete(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("知识文档ID不能为空");
        var removed = new java.util.concurrent.atomic.AtomicReference<KnowledgeDocument>();
        persistence.update(current -> {
            KnowledgeDocument existing = current.stream().filter(item -> item.id().equals(id.strip()))
                    .findFirst().orElseThrow(() -> new KnowledgeNotFoundException("知识文档不存在：" + id));
            List<KnowledgeDocument> documents = new ArrayList<>(current);
            documents.remove(existing);
            removed.set(existing);
            return documents;
        });
        return removed.get();
    }

    @Override
    public void attachRawSource(List<String> ids, StoredObject source) {
        persistence.attachRawSource(ids, source);
    }

    @Override
    public void attachRawSourceDocuments(List<KnowledgeDocument> documents, StoredObject source) {
        persistence.attachRawSourceDocuments(documents, source);
    }

    @Override
    public int count() {
        return readableDocuments().size();
    }

    private static void validate(KnowledgeDocument document) {
        if (document == null || document.id() == null || document.id().isBlank()) {
            throw new IllegalArgumentException("知识文档和ID不能为空");
        }
    }
}
