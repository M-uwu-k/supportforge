package com.supportforge.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;


@Component
public class KnowledgeQueryRewriter {


    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+");

    private final int maxQueries;


    public KnowledgeQueryRewriter(
            @Value(
                    "${supportforge.knowledge.retrieval.max-queries:4}"
            )
            int maxQueries
    ) {

        if (maxQueries < 1) {
            throw new IllegalArgumentException(
                    "maxQueries必须至少为1"
            );
        }

        this.maxQueries = maxQueries;
    }


    public List<String> rewrite(
            String query,
            KnowledgeCategory category
    ) {


        if (query == null || query.isBlank()) {
            return List.of();
        }


        String normalized =
                normalize(query);


        String lower =
                normalized.toLowerCase(
                        Locale.ROOT
                );


        Set<String> queries =
                new LinkedHashSet<>();


        addQuery(
                queries,
                normalized
        );


        addTopicQueries(
                queries,
                lower
        );


        addCategoryQuery(
                queries,
                category
        );


        return queries.stream()
                .limit(maxQueries)
                .toList();
    }


    private void addTopicQueries(
            Set<String> queries,
            String lowerQuery
    ) {

        if (containsAny(
                lowerQuery,
                "401",
                "登录",
                "认证",
                "凭证",
                "令牌"
        )) {
            addQuery(
                    queries,
                    "登录失败 认证 凭证 访问令牌 401"
            );
        }


        if (containsAny(
                lowerQuery,
                "崩溃",
                "闪退",
                "crash",
                "卡死"
        )) {
            addQuery(
                    queries,
                    "应用崩溃 闪退 缓存 日志 版本"
            );
        }


        if (containsAny(
                lowerQuery,
                "重复扣款",
                "扣了两次",
                "多扣",
                "重复支付"
        )) {
            addQuery(
                    queries,
                    "重复扣款 支付流水 金额 扣了两次"
            );
        }


        if (containsAny(
                lowerQuery,
                "退款",
                "退货",
                "退回来",
                "退回",
                "refund"
        )) {
            addQuery(
                    queries,
                    "退款申请 订单 支付记录 人工审核"
            );
        }


        if (containsAny(
                lowerQuery,
                "发票",
                "开票",
                "抬头",
                "税号",
                "invoice"
        )) {
            addQuery(
                    queries,
                    "发票 开票 抬头 税号 订单号"
            );
        }


        if (containsAny(
                lowerQuery,
                "物流",
                "快递",
                "配送",
                "到哪里",
                "订单状态"
        )) {
            addQuery(
                    queries,
                    "订单物流 快递 配送 订单状态"
            );
        }
    }


    private void addCategoryQuery(
            Set<String> queries,
            KnowledgeCategory category
    ) {
        if (category == null) {
            return;
        }


        switch (category) {
            case TECHNICAL ->
                    addQuery(
                            queries,
                            "技术故障 错误 排查 解决方案"
                    );

            case BILLING ->
                    addQuery(
                            queries,
                            "账单 支付 退款 发票 处理规则"
                    );

            case GENERAL ->
                    addQuery(
                            queries,
                            "订单 物流 会员 客服 帮助"
                    );
        }
    }


    private String normalize(
            String value
    ) {
        return WHITESPACE
                .matcher(value.strip())
                .replaceAll(" ");
    }


    private void addQuery(
            Set<String> queries,
            String query
    ) {
        if (query == null || query.isBlank()) {
            return;
        }


        queries.add(
                normalize(query)
        );
    }


    private boolean containsAny(
            String text,
            String... keywords
    ) {
        for (String keyword : keywords) {
            if (text.contains(
                    keyword.toLowerCase(
                            Locale.ROOT
                    )
            )) {
                return true;
            }
        }

        return false;
    }
}
