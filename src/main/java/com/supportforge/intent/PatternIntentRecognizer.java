package com.supportforge.intent;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


@Component
public class PatternIntentRecognizer {


    private static final Map<
            IntentCategory,
            List<String>
            > SPECIFIC_PATTERNS =
            createSpecificPatterns();


    private static final Map<
            IntentCategory,
            List<String>
            > GENERIC_PATTERNS =
            createGenericPatterns();


    public IntentCandidate recognize(
            String message
    ) {

        String normalized =
                normalize(message);


        PatternMatch specific =
                bestMatch(
                        normalized,
                        SPECIFIC_PATTERNS
                );


        if (specific.intent()
                != IntentCategory.OTHER) {

            return IntentCandidate.success(
                    specific.intent(),
                    specific.confidence(),
                    reasoning(
                            "细粒度关键词",
                            specific
                    )
            );
        }


        PatternMatch generic =
                bestMatch(
                        normalized,
                        GENERIC_PATTERNS
                );

        if (generic.intent()
                != IntentCategory.OTHER) {

            return IntentCandidate.success(
                    generic.intent(),
                    generic.confidence(),
                    reasoning(
                            "宽泛关键词",
                            generic
                    )
            );
        }


        return IntentCandidate.success(
                IntentCategory.OTHER,
                0.0,
                "没有命中任何意图关键词"
        );
    }


    private PatternMatch bestMatch(
            String message,
            Map<IntentCategory, List<String>>
                    patterns
    ) {

        IntentCategory bestIntent =
                IntentCategory.OTHER;

        double bestScore = 0.0;
        List<String> bestKeywords = List.of();


        for (Map.Entry<
                IntentCategory,
                List<String>
                > entry : patterns.entrySet()) {


            List<String> matchedKeywords =
                    entry.getValue()
                            .stream()
                            .filter(message::contains)
                            .toList();


            int hitCount =
                    matchedKeywords.size();

            if (hitCount == 0) {
                continue;
            }


            double score =
                    Math.min(
                            1.0,
                            0.50
                                    + 0.25
                                    * (hitCount - 1)
                    );


            if (score > bestScore) {
                bestIntent = entry.getKey();
                bestScore = score;
                bestKeywords = matchedKeywords;
            }
        }


        return new PatternMatch(
                bestIntent,
                bestScore,
                bestKeywords
        );
    }


    private String reasoning(
            String ruleType,
            PatternMatch match
    ) {
        return ruleType
                + "识别为"
                + match.intent().value()
                + "，命中："
                + String.join(
                "、",
                match.matchedKeywords()
        );
    }


    private String normalize(
            String message
    ) {
        if (message == null) {
            return "";
        }

        return message
                .strip()
                .toLowerCase(Locale.ROOT);
    }


    private static Map<
            IntentCategory,
            List<String>
            > createSpecificPatterns() {


        Map<IntentCategory, List<String>>
                patterns =
                new LinkedHashMap<>();

        patterns.put(
                IntentCategory.HUMAN_HANDOFF,
                List.of(
                        "转人工",
                        "人工客服",
                        "找人工",
                        "人工处理",
                        "升级处理"
                )
        );

        patterns.put(
                IntentCategory.ORDER_STATUS,
                List.of(
                        "订单状态",
                        "发货了吗",
                        "处理到哪",
                        "订单进度",
                        "order status"
                )
        );

        patterns.put(
                IntentCategory.LOGISTICS,
                List.of(
                        "物流",
                        "快递",
                        "配送",
                        "运单",
                        "delivery",
                        "shipping"
                )
        );

        patterns.put(
                IntentCategory.REFUND,
                List.of(
                        "退款",
                        "退货",
                        "退钱",
                        "refund",
                        "return"
                )
        );


        patterns.put(
                IntentCategory.INVOICE,
                List.of(
                        "发票",
                        "发票抬头",
                        "税号",
                        "电子发票",
                        "invoice"
                )
        );

        patterns.put(
                IntentCategory.PAYMENT_ISSUE,
                List.of(
                        "重复扣款",
                        "多扣",
                        "扣了两次",
                        "支付失败",
                        "扣费异常",
                        "payment failed"
                )
        );

        patterns.put(
                IntentCategory.ACCOUNT_SECURITY,
                List.of(
                        "账户被盗",
                        "账号被盗",
                        "异常登录",
                        "重置密码",
                        "两步验证",
                        "账号安全"
                )
        );

        patterns.put(
                IntentCategory.TECHNICAL_LOGIN,
                List.of(
                        "无法登录",
                        "登录失败",
                        "登录不了",
                        "登录时报错",
                        "401",
                        "验证码收不到"
                )
        );

        patterns.put(
                IntentCategory.TECHNICAL_CRASH,
                List.of(
                        "崩溃",
                        "闪退",
                        "500",
                        "程序报错",
                        "页面报错",
                        "crash"
                )
        );


        return Collections.unmodifiableMap(
                patterns
        );
    }


    private static Map<
            IntentCategory,
            List<String>
            > createGenericPatterns() {


        Map<IntentCategory, List<String>>
                patterns =
                new LinkedHashMap<>();


        patterns.put(
                IntentCategory.ESCALATION,
                List.of(
                        "投诉",
                        "找经理",
                        "负责人",
                        "supervisor"
                )
        );

        patterns.put(
                IntentCategory.COMPLAINT,
                List.of(
                        "太差",
                        "糟糕",
                        "一直没人处理",
                        "等了很久",
                        "horrible"
                )
        );


        patterns.put(
                IntentCategory.QUERY,
                List.of(
                        "怎么",
                        "什么",
                        "为什么",
                        "多少",
                        "哪里",
                        "是否",
                        "？",
                        "?",
                        "status"
                )
        );

        patterns.put(
                IntentCategory.REQUEST,
                List.of(
                        "帮我",
                        "请帮",
                        "我需要",
                        "please",
                        "help"
                )
        );

        patterns.put(
                IntentCategory.GREETING,
                List.of(
                        "你好",
                        "您好",
                        "嗨",
                        "hello",
                        "hi",
                        "早上好",
                        "晚上好"
                )
        );

        patterns.put(
                IntentCategory.BILLING,
                List.of(
                        "扣款",
                        "账单",
                        "费用",
                        "billing"
                )
        );

        patterns.put(
                IntentCategory.TECHNICAL,
                List.of(
                        "报错",
                        "错误",
                        "故障",
                        "error"
                )
        );

        patterns.put(
                IntentCategory.ACCOUNT,
                List.of(
                        "密码",
                        "邮箱",
                        "账户",
                        "账号",
                        "个人信息",
                        "password"
                )
        );

        patterns.put(
                IntentCategory.FEEDBACK,
                List.of(
                        "很满意",
                        "服务很棒",
                        "好评",
                        "谢谢你",
                        "感谢"
                )
        );


        return Collections.unmodifiableMap(
                patterns
        );
    }


    private record PatternMatch(
            IntentCategory intent,
            double confidence,
            List<String> matchedKeywords
    ) {


        private PatternMatch {
            intent = intent == null
                    ? IntentCategory.OTHER
                    : intent;


            matchedKeywords =
                    matchedKeywords == null
                            ? List.of()
                            : List.copyOf(
                            matchedKeywords
                    );
        }
    }
}
