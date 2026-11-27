package com.supportforge.knowledge;

import com.supportforge.persistence.port.KnowledgeDocumentPersistencePort;
import com.supportforge.objectstorage.StoredObject;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;


@Component
public class InMemoryKnowledgeBase
        implements KnowledgeBase {


    private static final Set<String> BUILT_IN_DOCUMENT_IDS =
            Set.of(
                    "tech-login-401",
                    "tech-crash",
                    "tech-server-500",
                    "billing-refund",
                    "billing-duplicate-charge",
                    "billing-invoice",
                    "general-order-status"
            );


    private final CopyOnWriteArrayList<KnowledgeDocument> documents =
            new CopyOnWriteArrayList<>(
                    List.of(

                    new KnowledgeDocument(
                            "tech-login-401",
                            "登录失败与错误码401",
                            KnowledgeCategory.TECHNICAL,

                            """
                            错误码401通常表示登录凭证无效、登录状态过期，
                            或访问令牌失效。建议用户确认账号信息，
                            重新登录并清除本地登录缓存。
                            不得要求用户提供密码或短信验证码。
                            """,

                            List.of(
                                    "401",
                                    "登录失败",
                                    "无法登录",
                                    "登录",
                                    "凭证",
                                    "令牌"
                            )
                    ),


                    new KnowledgeDocument(
                            "tech-crash",
                            "客户端崩溃处理",
                            KnowledgeCategory.TECHNICAL,
                            """
                            客户端发生崩溃时，应先记录问题发生时间、
                            软件版本和操作步骤。可以尝试清除缓存、
                            重启应用并升级到最新版本。
                            如果问题持续发生，应收集错误截图和日志。
                            """,
                            List.of(
                                    "崩溃",
                                    "闪退",
                                    "crash",
                                    "卡死",
                                    "缓存",
                                    "日志"
                            )
                    ),


                    new KnowledgeDocument(
                            "tech-server-500",
                            "服务端错误码500",
                            KnowledgeCategory.TECHNICAL,
                            """
                            错误码500通常表示服务端处理异常。
                            用户可以稍后重试并记录发生时间。
                            如果持续出现，应由技术人员结合请求编号
                            和服务端日志进一步排查。
                            """,
                            List.of(
                                    "500",
                                    "服务异常",
                                    "服务器错误",
                                    "服务端",
                                    "重试"
                            )
                    ),


                    new KnowledgeDocument(
                            "billing-refund",
                            "退款申请规则",
                            KnowledgeCategory.BILLING,
                            """
                            退款申请需要核验订单号、支付记录和退款原因。
                            客服不能直接承诺退款已经完成。
                            实际退款操作必须经过订单状态检查和人工审核。
                            """,
                            List.of(
                                    "退款",
                                    "退货",
                                    "退款申请",
                                    "原路退回",
                                    "人工审核"
                            )
                    ),


                    new KnowledgeDocument(
                            "billing-duplicate-charge",
                            "重复扣款处理",
                            KnowledgeCategory.BILLING,
                            """
                            出现重复扣款时，需要核对支付渠道、扣款时间、
                            金额和支付流水号。客服不能直接宣布扣款已经撤销。
                            核验确认后，应交由账单人员或人工客服处理。
                            """,
                            List.of(
                                    "重复扣款",
                                    "多扣",
                                    "扣款",
                                    "支付流水",
                                    "扣了两次"
                            )
                    ),


                    new KnowledgeDocument(
                            "billing-invoice",
                            "发票申请规则",
                            KnowledgeCategory.BILLING,
                            """
                            开具发票通常需要订单号、发票类型、
                            发票抬头和税号。修改已经开具的发票时，
                            需要先核验原发票及订单信息。
                            """,
                            List.of(
                                    "发票",
                                    "开票",
                                    "抬头",
                                    "税号",
                                    "电子发票"
                            )
                    ),


                    new KnowledgeDocument(
                            "general-order-status",
                            "订单与物流查询",
                            KnowledgeCategory.GENERAL,
                            """
                            查询订单和物流信息时，需要用户提供订单号。
                            在没有真实订单系统查询结果时，
                            客服不能编造物流状态和预计送达时间。
                            """,
                            List.of(
                                    "订单",
                                    "物流",
                                    "快递",
                                    "配送",
                                    "订单状态"
                            )
                    )
                    )

            );


    private KnowledgeDocumentPersistencePort documentStore;


    private KnowledgeHybridScorer hybridScorer =
            KnowledgeHybridScorer.defaults();


    @Autowired
    void configurePersistence(
            KnowledgeDocumentPersistencePort documentStore
    ) {

        this.documentStore = documentStore;


        loadPersistedDocuments();
    }


    @Autowired
    void configureHybridScorer(
            KnowledgeHybridScorer hybridScorer
    ) {

        this.hybridScorer =
                hybridScorer;
    }


    private void loadPersistedDocuments() {

        if (documentStore == null) {
            return;
        }


        List<KnowledgeDocument> persistedDocuments =
                documentStore.load();


        for (KnowledgeDocument persisted :
                persistedDocuments) {


            boolean alreadyExists = documents.stream()
                    .anyMatch(existing ->
                            existing.id().equals(
                                    persisted.id()
                            )
                    );


            if (!alreadyExists) {


                documents.add(persisted);
            }
        }
    }


    private void persistDocuments() {

        if (documentStore == null) {
            return;
        }

        documentStore.save(


                List.copyOf(documents)
        );
    }


    @Override
    public List<KnowledgeDocument> readableDocuments() {
        return List.copyOf(documents);
    }


    @Override
    public List<KnowledgeSearchResult> search(
            String query,
            KnowledgeCategory category,
            int limit
    ) {

        if (query == null || query.isBlank()) {
            return List.of();
        }


        String normalizedQuery =
                query.toLowerCase(Locale.ROOT);


        int safeLimit = Math.max(
                1,
                Math.min(limit, 10)
        );


        return documents.stream()


                .filter(document ->
                        category == null
                                || document.category() == category
                                || document.category()
                                == KnowledgeCategory.GENERAL
                )


                .map(document ->
                        calculateScore(
                                document,
                                normalizedQuery,
                                category
                        )
                )

                .filter(result -> result.score() > 0)


                .sorted(
                        Comparator
                                .comparingDouble(
                                        KnowledgeSearchResult::score
                                )
                                .reversed()
                )
                .limit(safeLimit)
                .toList();
    }


    private KnowledgeSearchResult calculateScore(
            KnowledgeDocument document,
            String normalizedQuery,
            KnowledgeCategory requestedCategory
    ) {
        return hybridScorer.score(
                document,
                normalizedQuery,
                requestedCategory
        );
    }


    @Override


    public synchronized KnowledgeDocument add(
            KnowledgeDocument document
    ) {

        if (document == null) {

            throw new IllegalArgumentException(
                    "知识文档不能为空"
            );
        }


        if (document.id() == null
                || document.id().isBlank()) {
            throw new IllegalArgumentException(
                    "知识文档ID不能为空"
            );
        }


        boolean idExists = documents.stream()
                .anyMatch(existing ->

                        existing.id().equals(document.id())
                );


        if (idExists) {
            throw new KnowledgeConflictException(
                    "知识文档ID已存在：" + document.id()
            );
        }


        documents.add(document);

        try {


            persistDocuments();
        } catch (RuntimeException exception) {


            documents.remove(document);


            throw exception;
        }


        return document;


    }

    @Override
    public synchronized KnowledgeDocument upsert(
            KnowledgeDocument document
    ) {
        if (document == null) {
            throw new IllegalArgumentException(
                    "知识文档不能为空"
            );
        }
        if (document.id() == null
                || document.id().isBlank()) {
            throw new IllegalArgumentException(
                    "知识文档ID不能为空"
            );
        }

        KnowledgeDocument existing = documents.stream()
                .filter(candidate -> candidate.id()
                        .equals(document.id()))
                .findFirst()
                .orElse(null);

        if (existing == null) {
            return add(document);
        }
        if (existing.equals(document)) {
            return existing;
        }
        if (BUILT_IN_DOCUMENT_IDS.contains(document.id())) {
            throw new KnowledgeConflictException(
                    "内置知识文档不能通过导入覆盖："
                            + document.id()
            );
        }

        int index = documents.indexOf(existing);
        documents.set(index, document);
        try {
            persistDocuments();
        } catch (RuntimeException exception) {
            documents.set(index, existing);
            throw exception;
        }
        return document;
    }

    @Override
    public synchronized void attachRawSource(
            List<String> documentIds,
            StoredObject source
    ) {
        if (documentStore == null
                || documentIds == null
                || documentIds.isEmpty()) {
            return;
        }
        documentStore.attachRawSource(
                List.copyOf(documentIds),
                source
        );
    }


    @Override


    public synchronized KnowledgeDocument delete(
            String documentId
    ) {

        if (documentId == null
                || documentId.isBlank()) {
            throw new IllegalArgumentException(
                    "知识文档ID不能为空"
            );
        }


        String normalizedId = documentId.strip();


        if (BUILT_IN_DOCUMENT_IDS.contains(normalizedId)) {

            throw new KnowledgeConflictException(
                    "内置知识文档不能删除：" + normalizedId
            );
        }


        KnowledgeDocument document =
                documents.stream()

                        .filter(existing ->
                                existing.id().equals(normalizedId)
                        )
                        .findFirst()

                        .orElseThrow(() ->
                                new KnowledgeNotFoundException(
                                        "知识文档不存在：" + normalizedId
                                )
                        );


        documents.remove(document);

        try {


            persistDocuments();
        } catch (RuntimeException exception) {


            documents.add(document);

            throw exception;
        }


        return document;
    }


    @Override
    public int count() {


        return documents.size();
    }


}
