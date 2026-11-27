package com.supportforge.knowledge;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class KnowledgeQueryRewriterTest {


    @Test
    void shouldReturnEmptyListForBlankQuery() {
        KnowledgeQueryRewriter rewriter =
                new KnowledgeQueryRewriter(4);

        assertEquals(List.of(), rewriter.rewrite(null, null));
        assertEquals(List.of(), rewriter.rewrite("   ", null));
    }


    @Test
    void shouldKeepNormalizedOriginalQueryFirst() {
        KnowledgeQueryRewriter rewriter =
                new KnowledgeQueryRewriter(4);

        List<String> queries = rewriter.rewrite(
                "  登录时   提示 401  ",
                KnowledgeCategory.TECHNICAL
        );

        assertEquals("登录时 提示 401", queries.getFirst());
        assertTrue(queries.contains(
                "登录失败 认证 凭证 访问令牌 401"
        ));
    }


    @Test
    void shouldExpandBillingAndRefundVocabulary() {
        KnowledgeQueryRewriter rewriter =
                new KnowledgeQueryRewriter(5);

        List<String> duplicateCharge = rewriter.rewrite(
                "同一笔订单扣了两次钱",
                KnowledgeCategory.BILLING
        );

        List<String> refund = rewriter.rewrite(
                "我想把这笔钱退回来",
                KnowledgeCategory.BILLING
        );

        assertTrue(duplicateCharge.contains(
                "重复扣款 支付流水 金额 扣了两次"
        ));
        assertTrue(refund.contains(
                "退款申请 订单 支付记录 人工审核"
        ));
    }


    @Test
    void shouldExpandCrashInvoiceAndLogisticsVocabulary() {
        KnowledgeQueryRewriter rewriter =
                new KnowledgeQueryRewriter(5);

        assertTrue(rewriter.rewrite(
                "客户端总是闪退",
                KnowledgeCategory.TECHNICAL
        ).contains(
                "应用崩溃 闪退 缓存 日志 版本"
        ));

        assertTrue(rewriter.rewrite(
                "怎么修改开票信息",
                KnowledgeCategory.BILLING
        ).contains(
                "发票 开票 抬头 税号 订单号"
        ));

        assertTrue(rewriter.rewrite(
                "快递现在到哪里了",
                KnowledgeCategory.GENERAL
        ).contains(
                "订单物流 快递 配送 订单状态"
        ));
    }


    @Test
    void shouldDeduplicateAndRespectMaximumQueryCount() {
        KnowledgeQueryRewriter rewriter =
                new KnowledgeQueryRewriter(2);

        List<String> queries = rewriter.rewrite(
                "登录失败 认证 凭证 访问令牌 401",
                KnowledgeCategory.TECHNICAL
        );

        assertEquals(2, queries.size());
        assertEquals(
                queries.size(),
                new HashSet<>(queries).size()
        );
        assertEquals(
                "登录失败 认证 凭证 访问令牌 401",
                queries.getFirst()
        );
    }


    @Test
    void shouldRejectInvalidMaximumQueryCount() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeQueryRewriter(0)
        );
    }
}
