package com.supportforge.api;

import com.supportforge.api.dto.KnowledgeDocumentRequest;
import com.supportforge.knowledge.KnowledgeBase;
import com.supportforge.knowledge.KnowledgeCategory;
import com.supportforge.knowledge.KnowledgeDocument;
import com.supportforge.knowledge.KnowledgeDocumentPage;
import com.supportforge.knowledge.KnowledgeSearchResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;


import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.supportforge.tool.ToolManager;


import com.supportforge.api.dto.KnowledgeBatchImportRequest;
import com.supportforge.knowledge.KnowledgeImportResult;
import com.supportforge.knowledge.KnowledgeImportService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;


@RestController
@RequestMapping("/knowledge")
@com.supportforge.security.RequiresAnyRole({com.supportforge.security.EnterpriseRole.ADMIN,
        com.supportforge.security.EnterpriseRole.KNOWLEDGE_EDITOR})
public class KnowledgeController {


    private final KnowledgeBase knowledgeBase;


    private static final String KNOWLEDGE_SEARCH_TOOL =
            "knowledge_search";

    private final ToolManager toolManager;

    private final KnowledgeImportService knowledgeImportService;


    public KnowledgeController(
            KnowledgeBase knowledgeBase,
            ToolManager toolManager,
            KnowledgeImportService knowledgeImportService
    ) {
        this.knowledgeBase = knowledgeBase;
        this.toolManager = toolManager;
        this.knowledgeImportService =
                knowledgeImportService;
    }


    @GetMapping("/search")
    @com.supportforge.security.RequiresAnyRole({com.supportforge.security.EnterpriseRole.ADMIN,
            com.supportforge.security.EnterpriseRole.AGENT, com.supportforge.security.EnterpriseRole.AUDITOR,
            com.supportforge.security.EnterpriseRole.KNOWLEDGE_EDITOR})
    public List<KnowledgeSearchResult> search(


            @RequestParam String query,


            @RequestParam(required = false)
            KnowledgeCategory category,


            @RequestParam(defaultValue = "3")
            int limit
    ) {

        return knowledgeBase.search(
                query,
                category,
                limit
        );
    }


    @GetMapping("/documents")
    @com.supportforge.security.RequiresAnyRole({com.supportforge.security.EnterpriseRole.ADMIN,
            com.supportforge.security.EnterpriseRole.AGENT, com.supportforge.security.EnterpriseRole.AUDITOR,
            com.supportforge.security.EnterpriseRole.KNOWLEDGE_EDITOR})
    public KnowledgeDocumentPage listDocuments(
            @RequestParam(required = false) KnowledgeCategory category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size
    ) {
        return knowledgeBase.listDocuments(category, page, size);
    }


    @GetMapping("/documents/{documentId}")
    @com.supportforge.security.RequiresAnyRole({com.supportforge.security.EnterpriseRole.ADMIN,
            com.supportforge.security.EnterpriseRole.AGENT, com.supportforge.security.EnterpriseRole.AUDITOR,
            com.supportforge.security.EnterpriseRole.KNOWLEDGE_EDITOR})
    public KnowledgeDocument getDocument(@PathVariable String documentId) {
        return knowledgeBase.getDocument(documentId);
    }


    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeDocument addDocument(


            @Valid

            @RequestBody
            KnowledgeDocumentRequest request
    ) {


        String documentId =
                request.id() == null
                        || request.id().isBlank()
                        ? UUID.randomUUID().toString()
                        : request.id().strip();


        KnowledgeDocument document =
                new KnowledgeDocument(
                        documentId,
                        request.title().strip(),
                        request.category(),
                        request.content().strip(),
                        request.keywords()
                );


        KnowledgeDocument addedDocument =
                knowledgeImportService.addDocument(document);


        toolManager.evictCache(
                KNOWLEDGE_SEARCH_TOOL
        );

        return addedDocument;
    }


    @GetMapping("/stats")
    @com.supportforge.security.RequiresAnyRole({com.supportforge.security.EnterpriseRole.ADMIN,
            com.supportforge.security.EnterpriseRole.AGENT, com.supportforge.security.EnterpriseRole.AUDITOR,
            com.supportforge.security.EnterpriseRole.KNOWLEDGE_EDITOR})
    public Map<String, Integer> stats() {


        return Map.of(
                "total_documents",
                knowledgeBase.count()
        );
    }

    @DeleteMapping("/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(
            @PathVariable
            String documentId
    ) {


        knowledgeBase.delete(documentId);

        toolManager.evictCache(
                KNOWLEDGE_SEARCH_TOOL
        );
    }


    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeImportResult importBatch(
            @Valid
            @RequestBody
            KnowledgeBatchImportRequest request
    ) {

        KnowledgeImportResult result =
                knowledgeImportService.importItems(
                        request.documents(),
                        request.defaultCategory()
                );


        evictCacheAfterImport(result);

        return result;
    }


    @PostMapping(
            value = "/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeImportResult uploadKnowledge(
            @RequestPart("file")
            MultipartFile file,

            @RequestParam(required = false)
            KnowledgeCategory category
    ) {


        byte[] bytes;

        try {


            bytes = file.getBytes();
        } catch (IOException exception) {


            throw new IllegalStateException(
                    "读取上传文件失败："
                            + file.getOriginalFilename(),
                    exception
            );
        }


        KnowledgeImportResult result =
                knowledgeImportService.importFile(
                        file.getOriginalFilename(),
                        bytes,
                        category
                );


        evictCacheAfterImport(result);


        return result;
    }


    private void evictCacheAfterImport(
            KnowledgeImportResult result
    ) {
        if (result.imported() > 0) {
            toolManager.evictCache(
                    KNOWLEDGE_SEARCH_TOOL
            );
        }
    }
}
