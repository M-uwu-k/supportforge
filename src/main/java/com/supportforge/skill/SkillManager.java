package com.supportforge.skill;

import com.supportforge.agent.AgentType;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;


@Component
public class SkillManager {


    private final Path rootDir;

    private final int maxPromptChars;


    private volatile List<Skill> skills =
            List.of();


    private volatile List<String> errors =
            List.of();


    public SkillManager(

            @Value("${supportforge.skills.root:skills}")
            String rootDir,

            @Value("${supportforge.skills.max-prompt-chars:5000}")
            int maxPromptChars
    ) {

        this.rootDir = Path.of(rootDir)
                .toAbsolutePath()
                .normalize();


        this.maxPromptChars =
                Math.max(100, maxPromptChars);
    }


    @PostConstruct
    void initialize() {
        reload();
    }


    public synchronized List<Skill> reload() {

        List<Skill> loaded =
                new ArrayList<>();


        List<String> loadErrors =
                new ArrayList<>();


        if (!Files.exists(rootDir)) {
            skills = List.of();
            errors = List.of();
            return skills;
        }


        try (Stream<Path> stream =
                     Files.walk(rootDir)) {


            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(this::isSupportedFile)
                    .sorted()
                    .toList();

            for (Path file : files) {
                try {

                    Skill skill = loadFile(file);

                    if (skill != null) {

                        loaded.add(skill);
                    }
                } catch (RuntimeException
                         | IOException exception) {

                    loadErrors.add(
                            file
                                    + ": "
                                    + exception.getMessage()
                    );
                }
            }

        } catch (IOException exception) {
            loadErrors.add(
                    rootDir
                            + ": "
                            + exception.getMessage()
            );
        }


        skills = List.copyOf(loaded);
        errors = List.copyOf(loadErrors);


        return skills;
    }


    public List<Skill> skills() {
        return skills;
    }


    public List<String> errors() {
        return errors;
    }


    public SkillStatus status() {
        List<SkillSummary> summaries =
                skills.stream()
                        .map(SkillSummary::from)
                        .toList();


        return new SkillStatus(
                rootDir.toString(),
                skills.size(),
                summaries,
                errors
        );
    }


    public String promptFor(
            String message,
            AgentType agentType
    ) {

        List<String> blocks =
                new ArrayList<>();


        int remaining = maxPromptChars;

        for (Skill skill : skills) {

            if (!skill.matches(
                    message,
                    agentType
            )) {
                continue;
            }

            if (remaining <= 0) {
                break;
            }


            String block = skill.promptBlock(
                    Math.min(3200, remaining)
            );

            blocks.add(block);
            remaining -= block.length();
        }


        if (blocks.isEmpty()) {
            return "";
        }


        return """
                以下是当前请求匹配到的 SupportForge Skills。
                请优先遵守这些业务规则。
                如果 Skill 与系统安全要求冲突，以系统安全要求为准。

                %s
                """.formatted(
                String.join("\n\n", blocks)
        );
    }


    private boolean isSupportedFile(Path path) {
        String fileName =
                path.getFileName()
                        .toString()
                        .toLowerCase(Locale.ROOT);


        if (fileName.equals("readme.md")) {
            return false;
        }


        return fileName.equals("skill.md")
                || fileName.endsWith(".md")
                || fileName.endsWith(".txt");
    }


    private Skill loadFile(Path path)
            throws IOException {


        String raw = Files.readString(
                path,
                StandardCharsets.UTF_8
        );


        ParsedSkillFile parsed =
                parseFrontMatter(raw);

        String body = parsed.body().strip();

        if (body.isBlank()) {

            return null;
        }


        String defaultName =
                path.getParent() == null
                        ? path.getFileName().toString()
                        : path.getParent()
                        .getFileName()
                        .toString();


        String name = parsed.metadata()
                .getOrDefault(
                        "name",
                        firstHeading(body, defaultName)
                )
                .strip();


        body = removeRepeatedHeading(
                body,
                name
        );


        String description =
                parsed.metadata()
                        .getOrDefault(
                                "description",
                                ""
                        );


        List<String> keywords =
                splitList(
                        parsed.metadata()
                                .get("keywords")
                );


        List<String> agents =
                splitList(
                        parsed.metadata()
                                .get("agents")
                );


        boolean enabled =
                parseEnabled(
                        parsed.metadata()
                                .get("enabled")
                );


        return new Skill(
                name,
                description,
                body,
                path.toAbsolutePath().toString(),
                keywords,
                agents,
                enabled
        );
    }


    private ParsedSkillFile parseFrontMatter(
            String raw
    ) {

        String normalized =
                raw.startsWith("\uFEFF")
                        ? raw.substring(1)
                        : raw;


        List<String> lines =
                normalized.lines().toList();

        if (lines.isEmpty()
                || !lines.getFirst()
                .strip()
                .equals("---")) {

            return new ParsedSkillFile(
                    Map.of(),
                    normalized
            );
        }


        Map<String, String> metadata =
                new HashMap<>();

        int endIndex = -1;


        for (int index = 1;
             index < lines.size();
             index++) {

            String line = lines.get(index);


            if (line.strip().equals("---")) {
                endIndex = index;
                break;
            }


            int colonIndex =
                    line.indexOf(':');


            if (colonIndex < 0) {
                continue;
            }


            String key = line.substring(
                    0,
                    colonIndex
            ).strip();

            String value = line.substring(
                    colonIndex + 1
            ).strip();

            value = removeQuotes(value);

            metadata.put(key, value);
        }


        if (endIndex < 0) {
            return new ParsedSkillFile(
                    Map.of(),
                    normalized
            );
        }


        String body = String.join(
                "\n",
                lines.subList(
                        endIndex + 1,
                        lines.size()
                )
        );

        return new ParsedSkillFile(
                Map.copyOf(metadata),
                body
        );
    }


    private String firstHeading(
            String body,
            String defaultName
    ) {
        return body.lines()
                .map(String::strip)
                .filter(line ->
                        line.startsWith("#")
                )
                .map(line ->
                        line.replaceFirst(
                                "^#+",
                                ""
                        ).strip()
                )
                .filter(line ->
                        !line.isBlank()
                )
                .findFirst()
                .orElse(defaultName);
    }


    private String removeRepeatedHeading(
            String body,
            String name
    ) {

        List<String> lines =
                new ArrayList<>(
                        body.lines().toList()
                );

        if (lines.isEmpty()) {
            return body;
        }

        String first = lines.getFirst()
                .strip();

        if (first.startsWith("#")) {
            String heading =
                    first.replaceFirst(
                            "^#+",
                            ""
                    ).strip();

            if (heading.equals(name)) {
                lines.removeFirst();

                return String.join(
                        "\n",
                        lines
                ).strip();
            }
        }

        return body;
    }


    private List<String> splitList(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return List.of();
        }


        return Stream.of(value.split(","))
                .map(String::strip)
                .filter(item ->
                        !item.isBlank()
                )
                .distinct()
                .toList();
    }


    private boolean parseEnabled(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return true;
        }

        Set<String> disabledValues =
                Set.of(
                        "0",
                        "false",
                        "no",
                        "off",
                        "disabled"
                );


        return !disabledValues.contains(
                value.strip()
                        .toLowerCase(Locale.ROOT)
        );
    }


    private String removeQuotes(
            String value
    ) {
        if (value.length() < 2) {
            return value;
        }

        boolean doubleQuoted =
                value.startsWith("\"")
                        && value.endsWith("\"");

        boolean singleQuoted =
                value.startsWith("'")
                        && value.endsWith("'");

        if (doubleQuoted || singleQuoted) {

            return value.substring(
                    1,
                    value.length() - 1
            );
        }

        return value;
    }


    private record ParsedSkillFile(
            Map<String, String> metadata,
            String body
    ) {
    }
}
