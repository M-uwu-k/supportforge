package com.supportforge.skill;

import com.supportforge.agent.AgentType;

import java.util.List;
import java.util.Locale;


public record Skill(
        String name,
        String description,
        String content,
        String path,
        List<String> keywords,
        List<String> agents,
        boolean enabled
) {


    public Skill {

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "Skill名称不能为空"
            );
        }


        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException(
                    "Skill正文不能为空"
            );
        }


        name = name.strip();

        description = description == null
                ? ""
                : description.strip();
        content = content.strip();

        path = path == null
                ? ""
                : path;


        keywords = keywords == null
                ? List.of()
                : keywords.stream()
                .filter(keyword ->

                        keyword != null
                                && !keyword.isBlank()
                )
                .map(String::strip)
                .distinct()
                .toList();


        agents = agents == null
                ? List.of()
                : agents.stream()
                .filter(agent ->
                        agent != null
                                && !agent.isBlank()
                )
                .map(agent ->
                        agent.strip()
                                .toLowerCase(Locale.ROOT)
                )
                .distinct()
                .toList();

    }


    public boolean matches(
            String message,
            AgentType agentType
    ) {

        if (!enabled) {
            return false;
        }


        if (!agents.isEmpty()
                && agentType != null) {
            String normalizedAgent =
                    agentType.name()
                            .toLowerCase(Locale.ROOT);


            if (!agents.contains(normalizedAgent)) {
                return false;
            }
        }


        if (keywords.isEmpty()) {
            return true;
        }


        String normalizedMessage =
                message == null
                        ? ""
                        : message.toLowerCase(Locale.ROOT);


        return keywords.stream()
                .anyMatch(keyword ->

                        normalizedMessage.contains(
                                keyword.toLowerCase(Locale.ROOT)
                        )
                );
    }


    public String promptBlock(int maxChars) {

        String descriptionText =
                description.isBlank()
                        ? ""
                        : "\n说明：" + description;


        String completeText =
                "### "
                        + name
                        + descriptionText
                        + "\n"
                        + content;


        int safeLimit = Math.max(1, maxChars);


        if (completeText.length() <= safeLimit) {
            return completeText;
        }


        if (safeLimit <= 3) {
            return completeText.substring(
                    0,
                    safeLimit
            );
        }


        return completeText.substring(
                        0,
                        safeLimit - 3
                )
                .stripTrailing()
                + "...";
    }
}
