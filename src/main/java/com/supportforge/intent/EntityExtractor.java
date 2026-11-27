package com.supportforge.intent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EntityExtractor {

    private static final Pattern ORDER_ID_PATTERN = Pattern.compile(
            "(?i)(?:订单号?|order(?:_id)?|#)"
                    + "\\s*[:：#]?\\s*"
                    + "([A-Za-z0-9_-]{4,32})"
    );

    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(今天|明天|昨天|本周|这周|下周|"
                    + "\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?)"
    );

    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "((?:¥|￥)\\s*\\d+(?:\\.\\d{1,2})?"
                    + "|\\d+(?:\\.\\d{1,2})?\\s*"
                    + "(?:元|块|rmb|cny|usd|美元))",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern HTTP_ERROR_CODE_PATTERN = Pattern.compile(
            "(?<!\\d)([45]\\d{2})(?!\\d)"
    );

    private static final Pattern NAMED_ERROR_CODE_PATTERN = Pattern.compile(
            "(?:错误码|报错码|error\\s*code|code)"
                    + "\\s*[:：#]?\\s*"
                    + "([A-Z][A-Z0-9_-]{2,16})",
            Pattern.CASE_INSENSITIVE
    );

    public Map<String, List<String>> extract(String message) {
        String safeMessage = message == null ? "" : message;

        Map<String, List<String>> entities = new LinkedHashMap<>();

        entities.put(
                "order_id",
                findMatches(ORDER_ID_PATTERN, safeMessage)
        );

        entities.put(
                "product",
                List.of()
        );

        entities.put(
                "date",
                findMatches(DATE_PATTERN, safeMessage)
        );

        entities.put(
                "amount",
                findMatches(AMOUNT_PATTERN, safeMessage)
        );

        entities.put(
                "error_code",
                mergeUnique(
                        findMatches(
                                HTTP_ERROR_CODE_PATTERN,
                                safeMessage
                        ),
                        findMatches(
                                NAMED_ERROR_CODE_PATTERN,
                                safeMessage
                        )
                )
        );

        return entities;
    }

    private List<String> findMatches(
            Pattern pattern,
            String message
    ) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(message);

        while (matcher.find()) {
            String value = matcher.group(1);

            if (value != null && !value.isBlank()) {
                values.add(value.strip());
            }
        }

        return List.copyOf(values);
    }

    private List<String> mergeUnique(
            List<String> first,
            List<String> second
    ) {
        Set<String> values = new LinkedHashSet<>();

        values.addAll(first);
        values.addAll(second);

        return new ArrayList<>(values);
    }
}