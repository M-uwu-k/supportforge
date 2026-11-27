package com.supportforge.evaluation;

import java.util.List;
import java.util.Objects;


public record DialogTestCase(

        String question,

        List<String> turns,

        String userId,

        String conversationId
) {

    public DialogTestCase {

        question = question == null
                ? ""
                : question.strip();


        turns = turns == null
                ? List.of()
                : turns.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .toList();


        userId = userId == null || userId.isBlank()
                ? "eval-user"
                : userId.strip();


        conversationId =
                conversationId == null
                        ? ""
                        : conversationId.strip();


        if (question.isBlank() && turns.isEmpty()) {
            throw new IllegalArgumentException(
                    "对话评测问题不能为空"
            );
        }
    }


    public List<String> questions() {
        return turns.isEmpty()
                ? List.of(question)
                : turns;
    }


    public static DialogTestCase single(
            String question
    ) {
        return new DialogTestCase(
                question,
                List.of(),
                "eval-user",
                ""
        );
    }


    public static DialogTestCase multi(
            List<String> turns
    ) {
        return new DialogTestCase(
                "",
                turns,
                "eval-user",
                ""
        );
    }
}
