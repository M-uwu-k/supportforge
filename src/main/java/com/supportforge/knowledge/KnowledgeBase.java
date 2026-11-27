package com.supportforge.knowledge;

import com.supportforge.objectstorage.StoredObject;

import java.util.List;

public interface KnowledgeBase {

    /** 当前身份可读的有效文档快照；企业实现先按租户和文档 ACL 过滤。 */
    List<KnowledgeDocument> readableDocuments();

    /** 浏览不依赖搜索关键词，也不受搜索 Top-K 限制；权限过滤必须早于统计和分页。 */
    default KnowledgeDocumentPage listDocuments(KnowledgeCategory category, int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page 必须大于0，size 必须在1到100之间");
        }
        List<KnowledgeDocument> filtered = readableDocuments().stream()
                .filter(document -> category == null || document.category() == category)
                .sorted(java.util.Comparator.comparing(KnowledgeDocument::title)
                        .thenComparing(KnowledgeDocument::id))
                .toList();
        return new KnowledgeDocumentPage(
                filtered.stream().skip((long) (page - 1) * size).limit(size).toList(),
                filtered.size(), page, size, (int) ((filtered.size() + (long) size - 1) / size)
        );
    }

    /** 全文查看与列表使用同一权限范围；不存在或未获授权均不返回正文。 */
    default KnowledgeDocument getDocument(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("知识文档ID不能为空");
        }
        return readableDocuments().stream().filter(document -> document.id().equals(documentId.strip()))
                .findFirst().orElseThrow(() -> new KnowledgeNotFoundException("知识文档不存在或无权查看"));
    }

    List<KnowledgeSearchResult> search(
            String query,
            KnowledgeCategory category,
            int limit
    );

    KnowledgeDocument add(
            KnowledgeDocument document
    );

    /**
     * 可重复导入入口：同ID同内容返回已有文档，同ID内容变化时更新。
     * 单篇创建仍调用add并保留重复ID冲突语义。
     */
    default KnowledgeDocument upsert(
            KnowledgeDocument document
    ) {
        return add(document);
    }

    /** 把上传原件的对象存储定位信息关联到已成功导入的知识版本。 */
    default void attachRawSource(
            List<String> documentIds,
            StoredObject source
    ) {
    }

    /** 上传使用完整文档限定版本；旧实现仍可按ID关联，数据库实现覆盖为版本校验。 */
    default void attachRawSourceDocuments(List<KnowledgeDocument> documents, StoredObject source) {
        attachRawSource(documents.stream().map(KnowledgeDocument::id).toList(), source);
    }


    KnowledgeDocument delete(
            String documentId
    );

    int count();
}
