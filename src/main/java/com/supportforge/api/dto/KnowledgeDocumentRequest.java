package com.supportforge.api.dto;

import com.supportforge.knowledge.KnowledgeCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;


public record KnowledgeDocumentRequest(


        String id,


        @NotBlank(message = "title 不能为空")
        String title,


        @NotNull(message = "category 不能为空")
        KnowledgeCategory category,


        @NotBlank(message = "content 不能为空")
        String content,


        @NotEmpty(message = "keywords 至少包含一个关键词")
        List<@NotBlank String> keywords
) {


    public KnowledgeDocumentRequest {
        if (keywords == null) {


            keywords = List.of();
        } else {


            keywords = keywords.stream()


                    .map(String::strip)

                    .filter(keyword ->
                            !keyword.isBlank()
                    )

                    .distinct()

                    .toList();
        }
    }
}
