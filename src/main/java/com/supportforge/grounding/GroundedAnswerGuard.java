package com.supportforge.grounding;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 回答生成后的边界检查：引用必须属于证据，数字事实必须在证据中出现。 */
@Component
public class GroundedAnswerGuard {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    public AnswerGroundingResult verify(
            String answer,
            GroundingDecision decision,
            Set<UUID> citedPointIds
    ) {
        return verify(answer, decision, citedPointIds, "");
    }

    /** 用户明确提供的订单号/金额可以保留；引用标签E1中的数字不属于业务数字。 */
    public AnswerGroundingResult verify(
            String answer, GroundingDecision decision, Set<UUID> citedPointIds,
            String userProvidedContext
    ) {
        List<String> issues = new ArrayList<>();

        if (answer == null || answer.isBlank()) {
            issues.add("回答正文不能为空");
            return new AnswerGroundingResult(false, issues);
        }
        if (!decision.allowed()) {
            issues.add("证据准入未通过，不能生成知识型回答");
            return new AnswerGroundingResult(false, issues);
        }
        Set<UUID> allowed = new HashSet<>();
        StringBuilder corpus = new StringBuilder();
        for (GroundingEvidence evidence : decision.evidence()) {
            allowed.add(evidence.pointId());
            corpus.append(evidence.content()).append('\n');
        }
        if (citedPointIds == null || citedPointIds.isEmpty()) {
            issues.add("回答没有引用任何准入证据");
        } else if (!allowed.containsAll(citedPointIds)) {
            issues.add("回答引用了本次准入证据之外的Point");
        }
        corpus.append('\n').append(userProvidedContext == null ? "" : userProvidedContext);
        String text = answer.replaceAll("\\[E[1-9][0-9]*\\]", "")
                .replaceAll("(?m)^\\s*\\d+[、)）]\\s*|^\\s*\\d+\\.\\s+", "");
        if (text.isBlank()) {
            issues.add("回答仅有引用或列表标记，没有有效正文");
        }
        Set<String> supportedNumbers = NUMBER.matcher(corpus).results()
                .map(match -> normalizeNumber(match.group()))
                .collect(java.util.stream.Collectors.toSet());
        Matcher numbers = NUMBER.matcher(text);
        while (numbers.find()) {
            if (!supportedNumbers.contains(normalizeNumber(numbers.group()))) {
                issues.add("回答包含证据未支持的数字：" + numbers.group());
            }
        }
        return new AnswerGroundingResult(issues.isEmpty(), issues);
    }

    private static String normalizeNumber(String value) {
        return new java.math.BigDecimal(value).stripTrailingZeros().toPlainString();
    }
}
