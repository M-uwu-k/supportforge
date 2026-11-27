package com.supportforge.persistence.port;

import com.supportforge.knowledge.KnowledgeDocument;
import com.supportforge.objectstorage.StoredObject;

import java.util.List;
import java.util.function.UnaryOperator;


public interface KnowledgeDocumentPersistencePort {

    List<KnowledgeDocument> load();

    void save(List<KnowledgeDocument> documents);

    /** 读最新快照并修改；数据库实现在同一事务内先加跨实例锁、再读、再写。 */
    default List<KnowledgeDocument> update(UnaryOperator<List<KnowledgeDocument>> change) {
        synchronized (this) {
            List<KnowledgeDocument> updated = List.copyOf(change.apply(load()));
            save(updated);
            return updated;
        }
    }

    /**
     * 把本次上传原件关联到成功导入文档的当前版本。JSON Adapter无需数据库关联，默认安全跳过。
     */
    default void attachRawSource(
            List<String> documentIds,
            StoredObject source
    ) {
    }

    default void attachRawSourceDocuments(List<KnowledgeDocument> documents, StoredObject source) {
        attachRawSource(documents.stream().map(KnowledgeDocument::id).toList(), source);
    }
}
