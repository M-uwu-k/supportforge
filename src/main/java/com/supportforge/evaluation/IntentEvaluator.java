package com.supportforge.evaluation;

import com.supportforge.intent.IntentCategory;
import com.supportforge.intent.IntentRecognizer;
import com.supportforge.intent.IntentResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;


@Component
public class IntentEvaluator {


    private static final List<IntentTestCase>
            DEFAULT_CASES = List.of(
            new IntentTestCase(
                    "我的订单什么时候到？",
                    "logistics"
            ),
            new IntentTestCase(
                    "帮我取消订单",
                    "request"
            ),
            new IntentTestCase(
                    "你们服务太差了！",
                    "complaint"
            ),
            new IntentTestCase(
                    "应用一直报500错误",
                    "technical_crash"
            ),
            new IntentTestCase(
                    "为什么扣了两次款？",
                    "payment_issue"
            ),
            new IntentTestCase(
                    "我要投诉，转人工！",
                    "human_handoff"
            ),
            new IntentTestCase(
                    "你好",
                    "greeting"
            ),
            new IntentTestCase(
                    "修改我的邮箱地址",
                    "account"
            ),
            new IntentTestCase(
                    "帮我开发票",
                    "invoice"
            ),
            new IntentTestCase(
                    "退款多久到账？",
                    "refund"
            ),
            new IntentTestCase(
                    "登录一直报401",
                    "technical_login"
            )
    );


    private final IntentRecognizer intentRecognizer;


    public IntentEvaluator(
            IntentRecognizer intentRecognizer
    ) {
        this.intentRecognizer = intentRecognizer;
    }


    public List<IntentTestCase> defaultCases() {
        return DEFAULT_CASES;
    }


    public IntentEvaluationReport evaluate(
            List<IntentTestCase> cases
    ) {

        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException(
                    "意图评测用例不能为空"
            );
        }


        List<IntentCaseResult> caseResults =
                new ArrayList<>();


        List<IntentCategory> expectedValues =
                new ArrayList<>();

        List<IntentCategory> predictedValues =
                new ArrayList<>();

        int correctCount = 0;


        for (IntentTestCase testCase : cases) {

            IntentCategory expected =
                    parseExpectedIntent(
                            testCase.expectedIntent()
                    );


            IntentResult recognition =
                    intentRecognizer.recognize(
                            testCase.message()
                    );

            IntentCategory predicted =
                    recognition.intent();


            boolean correct =
                    expected == predicted;

            if (correct) {

                correctCount++;
            }


            expectedValues.add(expected);
            predictedValues.add(predicted);


            caseResults.add(
                    new IntentCaseResult(
                            testCase.message(),
                            expected.value(),
                            predicted.value(),
                            correct,
                            recognition.confidence(),
                            recognition.reasoning()
                    )
            );
        }


        double accuracy =
                (double) correctCount
                        / cases.size();


        Set<IntentCategory> labels =
                new TreeSet<>(
                        Comparator.comparing(
                                IntentCategory::value
                        )
                );


        labels.addAll(expectedValues);
        labels.addAll(predictedValues);


        Map<String, IntentClassMetrics> perClass =
                new TreeMap<>();


        for (IntentCategory label : labels) {

            long truePositive = 0;
            long falsePositive = 0;
            long falseNegative = 0;


            for (int index = 0;
                 index < expectedValues.size();
                 index++) {

                IntentCategory expected =
                        expectedValues.get(index);

                IntentCategory predicted =
                        predictedValues.get(index);


                if (expected == label
                        && predicted == label) {
                    truePositive++;
                }


                if (expected != label
                        && predicted == label) {
                    falsePositive++;
                }


                if (expected == label
                        && predicted != label) {
                    falseNegative++;
                }


            }


            double precision =
                    truePositive + falsePositive == 0
                            ? 0.0
                            : (double) truePositive
                            / (truePositive + falsePositive);


            double recall =
                    truePositive + falseNegative == 0
                            ? 0.0
                            : (double) truePositive
                            / (truePositive + falseNegative);


            double f1 =
                    precision + recall == 0
                            ? 0.0
                            : 2.0
                            * precision
                            * recall
                            / (precision + recall);


            perClass.put(
                    label.value(),
                    new IntentClassMetrics(
                            truePositive,
                            falsePositive,
                            falseNegative,
                            round4(precision),
                            round4(recall),
                            round4(f1)
                    )
            );
        }


        double macroF1 =
                perClass.values()
                        .stream()
                        .mapToDouble(
                                IntentClassMetrics::f1
                        )
                        .average()
                        .orElse(0.0);


        return new IntentEvaluationReport(
                round4(accuracy),
                round4(macroF1),
                cases.size(),
                correctCount,
                perClass,
                caseResults
        );
    }


    private IntentCategory parseExpectedIntent(
            String value
    ) {

        for (IntentCategory category
                : IntentCategory.values()) {


            if (category.value()
                    .equalsIgnoreCase(value)) {
                return category;
            }
        }


        throw new IllegalArgumentException(
                "不支持的预期意图：" + value
        );
    }


    private double round4(double value) {
        return Math.round(value * 10_000.0)
                / 10_000.0;
    }
}
