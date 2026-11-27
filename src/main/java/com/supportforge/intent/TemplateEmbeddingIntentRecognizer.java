package com.supportforge.intent;

import com.supportforge.persistence.port.IntentLearningPersistencePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


@Component
public class TemplateEmbeddingIntentRecognizer {

    private static final Map<
            IntentCategory,
            List<String>
            > TEMPLATES =
            createTemplates();

    private final int dimensions;


    private final Map<
            IntentCategory,
            List<double[]>
            > templateVectors;


    private final Map<String, LearnedTemplate>
            learnedTemplates =
            new LinkedHashMap<>();


    private final IntentLearningPersistencePort learningStore;
    private String loadedScope;


    public TemplateEmbeddingIntentRecognizer(
            int dimensions
    ) {
        this(
                dimensions,
                null
        );
    }


    @Autowired
    public TemplateEmbeddingIntentRecognizer(
            @Value(
                    "${supportforge.intent.embedding.dimensions:256}"
            )
            int dimensions,

            IntentLearningPersistencePort learningStore
    ) {
        if (dimensions < 32) {
            throw new IllegalArgumentException(
                    "本地意图向量维度不能小于32"
            );
        }

        this.dimensions = dimensions;


        this.templateVectors =
                createTemplateVectors();

        this.learningStore =
                learningStore;


        reloadLearnedTemplates();
    }


    public static TemplateEmbeddingIntentRecognizer
    defaults() {
        return new TemplateEmbeddingIntentRecognizer(
                256
        );
    }


    public synchronized boolean learn(
            String message,
            IntentCategory correctIntent
    ) {
        activateScope();

        String normalized =
                normalize(message);


        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    "学习样本消息不能为空"
            );
        }

        if (correctIntent == null) {
            throw new IllegalArgumentException(
                    "学习样本的正确意图不能为空"
            );
        }

        if (correctIntent == IntentCategory.OTHER) {
            throw new IllegalArgumentException(
                    "不能把OTHER作为学习模板的正确意图"
            );
        }


        LearnedTemplate existing =
                learnedTemplates.get(normalized);


        if (existing != null
                && existing.intent()
                == correctIntent) {

            return false;
        }


        LearnedTemplate next =
                createLearnedTemplate(
                        message,
                        correctIntent
                );

        if (learningStore == null) {


            learnedTemplates.put(
                    normalized,
                    next
            );

            return true;
        }


        boolean changed =
                learningStore.upsert(
                        message,
                        correctIntent
                );


        reloadLearnedTemplates();

        return changed;
    }


    public synchronized int learnedTemplateCount() {
        activateScope();
        return learnedTemplates.size();
    }


    private void reloadLearnedTemplates() {

        Map<String, LearnedTemplate> restored =
                new LinkedHashMap<>();

        if (learningStore != null) {

            for (IntentLearningSample sample :
                    learningStore.findAll()) {

                String normalized =
                        normalize(
                                sample.message()
                        );


                restored.put(
                        normalized,
                        createLearnedTemplate(
                                sample.message(),
                                sample.category()
                        )
                );
            }
        }


        learnedTemplates.clear();
        learnedTemplates.putAll(restored);
        loadedScope = learningStore == null ? "memory" : learningStore.scopeKey();
    }

    private void activateScope() {
        String scope = learningStore == null ? "memory" : learningStore.scopeKey();
        if (!java.util.Objects.equals(loadedScope, scope)) reloadLearnedTemplates();
    }


    private LearnedTemplate createLearnedTemplate(
            String message,
            IntentCategory correctIntent
    ) {
        String normalized =
                normalize(message);

        String displayText =
                message.strip();

        return new LearnedTemplate(
                correctIntent,
                displayText,
                localEmbedding(normalized)
        );
    }


    public synchronized IntentCandidate recognize(
            String message
    ) {
        activateScope();

        String normalized =
                normalize(message);


        if (normalized.isBlank()) {
            return IntentCandidate.success(
                    IntentCategory.OTHER,
                    0.0,
                    "消息为空，无法进行模板向量匹配"
            );
        }


        LearnedTemplate exactLearned =
                learnedTemplates.get(normalized);

        if (exactLearned != null) {
            return IntentCandidate.success(
                    exactLearned.intent(),
                    1.0,
                    "完全命中学习样本“"
                            + exactLearned.text()
                            + "”"
            );
        }

        try {

            double[] messageVector =
                    localEmbedding(normalized);


            IntentCategory bestIntent =
                    IntentCategory.OTHER;

            double bestScore = 0.0;
            String bestTemplate = "";


            for (LearnedTemplate learned :
                    learnedTemplates.values()) {

                double score =
                        cosineSimilarity(
                                messageVector,
                                learned.vector()
                        );


                if (score > bestScore) {
                    bestIntent =
                            learned.intent();

                    bestScore =
                            score;

                    bestTemplate =
                            learned.text();
                }
            }


            for (Map.Entry<
                    IntentCategory,
                    List<double[]>
                    > entry :
                    templateVectors.entrySet()) {


                List<String> texts =
                        TEMPLATES.get(entry.getKey());


                List<double[]> vectors =
                        entry.getValue();

                for (int index = 0;
                     index < vectors.size();
                     index++) {


                    double score =
                            cosineSimilarity(
                                    messageVector,
                                    vectors.get(index)
                            );


                    if (score > bestScore) {

                        bestIntent = entry.getKey();
                        bestScore = score;
                        bestTemplate = texts.get(index);
                    }
                }
            }


            if (bestIntent == IntentCategory.OTHER
                    || bestScore <= 0.0) {

                return IntentCandidate.success(
                        IntentCategory.OTHER,
                        0.0,
                        "没有找到相似度为正的意图模板"
                );
            }


            return IntentCandidate.success(
                    bestIntent,
                    bestScore,
                    String.format(
                            Locale.ROOT,
                            "与模板“%s”最相似，余弦相似度为%.3f",
                            bestTemplate,
                            bestScore
                    )
            );
        } catch (RuntimeException exception) {


            return IntentCandidate.failure(
                    "本地模板向量识别失败："
                            + exception.getMessage()
            );
        }
    }


    private Map<
            IntentCategory,
            List<double[]>
            > createTemplateVectors() {

        Map<IntentCategory, List<double[]>>
                result =
                new LinkedHashMap<>();


        TEMPLATES.forEach((intent, texts) -> {


            List<double[]> vectors =
                    texts.stream()
                            .map(this::localEmbedding)
                            .toList();

            result.put(
                    intent,
                    vectors
            );
        });


        return Collections.unmodifiableMap(
                result
        );
    }


    private double[] localEmbedding(
            String text
    ) {


        String normalized =
                normalize(text);


        double[] vector =
                new double[dimensions];


        Set<String> tokens =
                new LinkedHashSet<>();


        for (int tokenLength = 1;
             tokenLength <= 3;
             tokenLength++) {

            if (normalized.length()
                    < tokenLength) {
                continue;
            }

            for (int start = 0;
                 start + tokenLength
                         <= normalized.length();
                 start++) {


                tokens.add(
                        normalized.substring(
                                start,
                                start + tokenLength
                        )
                );
            }
        }


        if (tokens.isEmpty()) {
            tokens.add(normalized);
        }


        MessageDigest digest =
                md5();

        for (String token : tokens) {


            byte[] hash =
                    digest.digest(
                            token.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );


            long bucket =
                    ((hash[0] & 0xffL) << 24)
                            | ((hash[1] & 0xffL) << 16)
                            | ((hash[2] & 0xffL) << 8)
                            | (hash[3] & 0xffL);


            int vectorIndex =
                    (int) (
                            bucket
                                    % dimensions
                    );


            double contribution =
                    (hash[4] & 0xff) % 2 == 0
                            ? 1.0
                            : -1.0;


            vector[vectorIndex] +=
                    contribution;
        }


        return vector;
    }


    private double cosineSimilarity(
            double[] first,
            double[] second
    ) {
        if (first.length != second.length) {
            throw new IllegalArgumentException(
                    "参与比较的向量维度不一致"
            );
        }


        double dotProduct = 0.0;
        double firstNorm = 0.0;
        double secondNorm = 0.0;

        for (int index = 0;
             index < first.length;
             index++) {


            dotProduct +=
                    first[index]
                            * second[index];

            firstNorm +=
                    first[index]
                            * first[index];

            secondNorm +=
                    second[index]
                            * second[index];
        }


        if (firstNorm == 0.0
                || secondNorm == 0.0) {
            return 0.0;
        }


        return dotProduct
                / (
                Math.sqrt(firstNorm)
                        * Math.sqrt(secondNorm)
        );
    }


    private MessageDigest md5() {
        try {
            return MessageDigest.getInstance(
                    "MD5"
            );
        } catch (NoSuchAlgorithmException exception) {


            throw new IllegalStateException(
                    "当前Java运行环境不支持MD5",
                    exception
            );
        }
    }


    private String normalize(
            String text
    ) {
        if (text == null) {
            return "";
        }

        return text
                .strip()
                .toLowerCase(Locale.ROOT);
    }


    private static Map<
            IntentCategory,
            List<String>
            > createTemplates() {

        Map<IntentCategory, List<String>>
                templates =
                new LinkedHashMap<>();

        templates.put(
                IntentCategory.QUERY,
                List.of(
                        "我的订单状态是什么？",
                        "如何重置密码？",
                        "快递什么时候到？"
                )
        );

        templates.put(
                IntentCategory.COMPLAINT,
                List.of(
                        "等了好几个小时！",
                        "服务太差了！",
                        "一直没人处理！"
                )
        );

        templates.put(
                IntentCategory.REQUEST,
                List.of(
                        "帮我取消订单",
                        "我需要修改地址",
                        "请协助退款"
                )
        );

        templates.put(
                IntentCategory.GREETING,
                List.of(
                        "你好",
                        "嗨，有人吗",
                        "早上好"
                )
        );

        templates.put(
                IntentCategory.ESCALATION,
                List.of(
                        "我要投诉！",
                        "转人工客服",
                        "找你们经理"
                )
        );

        templates.put(
                IntentCategory.TECHNICAL,
                List.of(
                        "应用一直崩溃",
                        "无法登录",
                        "出现500错误"
                )
        );


        templates.put(
                IntentCategory.BILLING,
                List.of(
                        "为什么扣了两次款？",
                        "申请退款",
                        "发票问题"
                )
        );

        templates.put(
                IntentCategory.ACCOUNT,
                List.of(
                        "修改邮箱",
                        "注销账户",
                        "更新个人信息"
                )
        );

        templates.put(
                IntentCategory.FEEDBACK,
                List.of(
                        "服务很棒！",
                        "非常满意",
                        "给个好评"
                )
        );

        templates.put(
                IntentCategory.ORDER_STATUS,
                List.of(
                        "我的订单现在是什么状态？",
                        "订单有没有发货？",
                        "订单处理到哪一步了？"
                )
        );

        templates.put(
                IntentCategory.LOGISTICS,
                List.of(
                        "快递什么时候到？",
                        "物流一直不更新",
                        "配送要多久？"
                )
        );


        templates.put(
                IntentCategory.REFUND,
                List.of(
                        "我要申请退款",
                        "退货退款怎么处理？",
                        "退款多久到账？"
                )
        );

        templates.put(
                IntentCategory.INVOICE,
                List.of(
                        "帮我开发票",
                        "发票抬头怎么改？",
                        "电子发票在哪里？"
                )
        );

        templates.put(
                IntentCategory.PAYMENT_ISSUE,
                List.of(
                        "为什么重复扣款？",
                        "支付失败怎么办？",
                        "这个月多扣了钱"
                )
        );

        templates.put(
                IntentCategory.ACCOUNT_SECURITY,
                List.of(
                        "账户被盗了",
                        "发现异常登录",
                        "我要重置密码"
                )
        );

        templates.put(
                IntentCategory.TECHNICAL_LOGIN,
                List.of(
                        "登录一直报401",
                        "验证码收不到",
                        "无法登录账号"
                )
        );

        templates.put(
                IntentCategory.TECHNICAL_CRASH,
                List.of(
                        "应用一直崩溃",
                        "页面报500错误",
                        "系统闪退"
                )
        );

        templates.put(
                IntentCategory.HUMAN_HANDOFF,
                List.of(
                        "转人工客服",
                        "我要找人工",
                        "请升级处理"
                )
        );


        return Collections.unmodifiableMap(
                templates
        );
    }


    private record LearnedTemplate(
            IntentCategory intent,
            String text,
            double[] vector
    ) {
    }
}
