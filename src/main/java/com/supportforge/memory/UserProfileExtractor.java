package com.supportforge.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;


@Component
public class UserProfileExtractor {


    private static final Pattern ENGLISH_LANGUAGE =
            Pattern.compile(
                    "(?:请|以后|后面|接下来)?"
                            + "(?:用|使用)"
                            + "(?:英文|英语)"
                            + "(?:回答|回复)?"
                            + "|reply\\s+in\\s+english",
                    Pattern.CASE_INSENSITIVE
            );


    private static final Pattern CHINESE_LANGUAGE =
            Pattern.compile(
                    "(?:请|以后|后面|接下来)?"
                            + "(?:用|使用)"
                            + "(?:中文|汉语)"
                            + "(?:回答|回复)?"
            );


    private static final Pattern NEGATIVE_ENGLISH =
            Pattern.compile(
                    "(?:不要|别)"
                            + ".{0,3}"
                            + "(?:英文|英语)"
            );

    private static final Pattern NEGATIVE_CHINESE =
            Pattern.compile(
                    "(?:不要|别)"
                            + ".{0,3}"
                            + "(?:中文|汉语)"
            );

    private static final Pattern CONCISE_STYLE =
            Pattern.compile(
                    "(?:简短|简洁|直接)"
                            + "(?:一点|一些|些)?"
            );

    private static final Pattern NEGATIVE_CONCISE =
            Pattern.compile(
                    "(?:不要|别)"
                            + ".{0,3}"
                            + "(?:简短|简洁)"
            );


    private static final Pattern DETAILED_STYLE =
            Pattern.compile(
                    "(?:详细|具体)"
                            + "(?:一点|一些|些)?"
                            + "|展开说明"
            );

    private final boolean enabled;


    public UserProfileExtractor(
            @Value(
                    "${supportforge.memory.profile.enabled:true}"
            )
            boolean enabled
    ) {
        this.enabled = enabled;
    }


    public static UserProfileExtractor defaults() {
        return new UserProfileExtractor(true);
    }


    public Map<String, String> update(
            Map<String, String> existingProfile,
            String userMessage
    ) {

        Map<String, String> result =
                new LinkedHashMap<>();

        if (existingProfile != null) {


            result.putAll(existingProfile);
        }


        if (!enabled
                || userMessage == null
                || userMessage.isBlank()) {

            return immutable(result);
        }


        if (ENGLISH_LANGUAGE.matcher(userMessage).find()
                && !NEGATIVE_ENGLISH
                .matcher(userMessage)
                .find()) {

            result.put(
                    "preferred_language",
                    "英文"
            );

        }


        if (CHINESE_LANGUAGE.matcher(userMessage).find()
                && !NEGATIVE_CHINESE
                .matcher(userMessage)
                .find()) {

            result.put(
                    "preferred_language",
                    "中文"
            );
        }


        if (CONCISE_STYLE.matcher(userMessage).find()
                && !NEGATIVE_CONCISE
                .matcher(userMessage)
                .find()) {

            result.put(
                    "response_style",
                    "简洁"
            );

        }

        if (DETAILED_STYLE.matcher(userMessage).find()) {
            result.put(
                    "response_style",
                    "详细"
            );
        }


        return immutable(result);
    }


    private Map<String, String> immutable(
            Map<String, String> profile
    ) {
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(profile)
        );
    }
}
