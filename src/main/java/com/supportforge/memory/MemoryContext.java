package com.supportforge.memory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


public record MemoryContext(


        List<MemoryMessage> recentMessages,


        String summary,


        List<String> relevantHistory,


        Map<String, String> userProfile
) {


    public MemoryContext {
        recentMessages = recentMessages == null
                ? List.of()
                : List.copyOf(recentMessages);

        summary = summary == null
                ? ""
                : summary.strip();

        relevantHistory = relevantHistory == null
                ? List.of()
                : List.copyOf(relevantHistory);

        userProfile = userProfile == null
                ? Map.of()
                : Map.copyOf(userProfile);
    }


    public static MemoryContext empty() {
        return new MemoryContext(
                List.of(),
                "",
                List.of(),
                Map.of()
        );
    }


    public String toPrompt() {

        StringBuilder prompt = new StringBuilder();

        if (!userProfile.isEmpty()) {
            prompt.append("[用户画像]\n");


            userProfile.forEach((key, value) ->
                    prompt.append(key)
                            .append("：")
                            .append(value)
                            .append("\n")
            );
        }

        if (!relevantHistory.isEmpty()) {

            appendSeparator(prompt);
            prompt.append("[相关历史]\n");

            for (String history : relevantHistory) {
                prompt.append("- ")
                        .append(history)
                        .append("\n");
            }
        }

        if (!summary.isBlank()) {
            appendSeparator(prompt);
            prompt.append("[对话摘要]\n")
                    .append(summary)
                    .append("\n");
        }

        if (!recentMessages.isEmpty()) {
            appendSeparator(prompt);


            String recentText =
                    recentMessages.stream()
                            .map(message -> {
                                String role =
                                        message.role()
                                                == MemoryRole.USER
                                                ? "用户"
                                                : "助手";

                                return role
                                        + "："
                                        + message.content();
                            })
                            .collect(
                                    Collectors.joining("\n")
                            );

            prompt.append(recentText);
        }

        return prompt.toString().strip();
    }


    private void appendSeparator(
            StringBuilder prompt
    ) {
        if (!prompt.isEmpty()) {
            prompt.append("\n");
        }
    }
}
