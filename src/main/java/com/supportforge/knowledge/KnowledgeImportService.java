package com.supportforge.knowledge;

import com.supportforge.objectstorage.ObjectStoragePort;
import com.supportforge.objectstorage.StoredObject;
import com.supportforge.knowledge.ingestion.KnowledgeIngestionPipeline;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Service
public class KnowledgeImportService {


    private static final Pattern MARKDOWN_TITLE =
            Pattern.compile(
                    "(?m)^\\s*#\\s+(.+?)\\s*$"
            );


    private static final List<String> DOMAIN_TERMS =
            List.of(
                    "退款",
                    "退货",
                    "订单",
                    "订单号",
                    "物流",
                    "快递",
                    "配送",
                    "登录",
                    "登录失败",
                    "认证",
                    "凭证",
                    "令牌",
                    "401",
                    "403",
                    "404",
                    "500",
                    "崩溃",
                    "闪退",
                    "支付",
                    "扣款",
                    "重复扣款",
                    "发票",
                    "开票",
                    "会员",
                    "积分",
                    "密码",
                    "账户"
            );

    private final KnowledgeBase knowledgeBase;
    private final ObjectMapper objectMapper;
    private final ObjectStoragePort objectStorage;
    private final KnowledgeIngestionPipeline ingestionPipeline;
    private final long maximumFileBytes;


    public KnowledgeImportService(
            KnowledgeBase knowledgeBase,
            ObjectMapper objectMapper,
            ObjectStoragePort objectStorage,
            KnowledgeIngestionPipeline ingestionPipeline,

            @Value(
                    "${supportforge.knowledge.import.maximum-file-bytes:1048576}"
            )
            long maximumFileBytes
    ) {
        if (maximumFileBytes < 1) {
            throw new IllegalArgumentException(
                    "maximumFileBytes必须至少为1"
            );
        }


        this.knowledgeBase = knowledgeBase;
        this.objectMapper = objectMapper;
        this.objectStorage = objectStorage;
        this.ingestionPipeline = ingestionPipeline;
        this.maximumFileBytes = maximumFileBytes;
    }

    /**
     * 单篇手动新增与批量、文件导入共用清洗、分块和索引调度流水线。
     * add 保留“重复 ID 返回冲突”的语义；只有保存成功后才进入流水线。
     */
    public KnowledgeDocument addDocument(KnowledgeDocument document) {
        KnowledgeDocument added = knowledgeBase.add(document);
        ingestionPipeline.ingest(added);
        return added;
    }


    public KnowledgeImportResult importItems(
            List<KnowledgeImportItem> items,
            KnowledgeCategory defaultCategory
    ) {

        if (items == null || items.isEmpty()) {
            return new KnowledgeImportResult(
                    0,
                    0,
                    0,
                    List.of(),
                    List.of()
            );
        }


        List<KnowledgeDocument> importedDocuments =
                new ArrayList<>();


        List<String> errors =
                new ArrayList<>();


        for (int index = 0;
             index < items.size();
             index++) {

            KnowledgeImportItem item =
                    items.get(index);

            try {

                KnowledgeDocument document =
                        toDocument(
                                item,
                                defaultCategory
                        );


                KnowledgeDocument imported =
                        knowledgeBase.upsert(document);


                ingestionPipeline.ingest(imported);
                importedDocuments.add(imported);
            } catch (RuntimeException exception) {

                errors.add(
                        errorMessage(
                                index,
                                item,
                                exception
                        )
                );
            }
        }


        return new KnowledgeImportResult(
                items.size(),
                importedDocuments.size(),
                errors.size(),
                importedDocuments,
                errors
        );
    }


    public KnowledgeImportResult importFile(
            String filename,
            byte[] bytes,
            KnowledgeCategory defaultCategory
    ) {

        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException(
                    "文件名不能为空"
            );
        }

        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException(
                    "上传文件不能为空"
            );
        }

        if (bytes.length > maximumFileBytes) {
            throw new IllegalArgumentException(
                    "上传文件超过大小限制："
                            + maximumFileBytes
                            + "字节"
            );
        }


        String safeFilename =
                Path.of(filename)
                        .getFileName()
                        .toString();


        String extension =
                extension(safeFilename);

        String contentType = contentType(extension);


        StoredObject source = objectStorage.storeRawKnowledge(
                safeFilename,
                contentType,
                bytes
        );


        KnowledgeImportResult result = switch (extension) {
            case "txt" ->
                    importText(
                            safeFilename,
                            bytes,
                            defaultCategory
                    );

            case "md" ->
                    importMarkdown(
                            safeFilename,
                            bytes,
                            defaultCategory
                    );

            case "json" ->
                    importJson(
                            safeFilename,
                            bytes,
                            defaultCategory
                    );

            default ->
                    throw new IllegalArgumentException(
                            "不支持的知识文件类型："
                                    + extension
                    );
        };

        if (!result.documents().isEmpty()) {
            knowledgeBase.attachRawSourceDocuments(
                    result.documents(),
                    source
            );
        }
        return result;
    }

    private static String contentType(String extension) {
        return switch (extension) {
            case "txt" -> "text/plain; charset=UTF-8";
            case "md" -> "text/markdown; charset=UTF-8";
            case "json" -> "application/json; charset=UTF-8";
            default -> throw new IllegalArgumentException(
                    "不支持的知识文件类型：" + extension
            );
        };
    }


    private KnowledgeImportResult importText(
            String filename,
            byte[] bytes,
            KnowledgeCategory defaultCategory
    ) {
        String content =
                decodeUtf8(bytes);


        KnowledgeImportItem item =
                new KnowledgeImportItem(
                        null,
                        baseName(filename),
                        resolvedDefaultCategory(
                                defaultCategory
                        ),
                        content,
                        List.of()
                );

        return importItems(
                List.of(item),
                defaultCategory
        );
    }


    private KnowledgeImportResult importMarkdown(
            String filename,
            byte[] bytes,
            KnowledgeCategory defaultCategory
    ) {
        String content =
                decodeUtf8(bytes);


        Matcher matcher =
                MARKDOWN_TITLE.matcher(content);


        String title =
                matcher.find()
                        ? matcher.group(1).strip()
                        : baseName(filename);

        KnowledgeImportItem item =
                new KnowledgeImportItem(
                        null,
                        title,
                        resolvedDefaultCategory(
                                defaultCategory
                        ),
                        content,
                        List.of()
                );

        return importItems(
                List.of(item),
                defaultCategory
        );
    }


    private KnowledgeImportResult importJson(
            String filename,
            byte[] bytes,
            KnowledgeCategory defaultCategory
    ) {
        try {

            String json =
                    new String(
                            bytes,
                            StandardCharsets.UTF_8
                    );


            KnowledgeImportItem[] loaded =
                    objectMapper.readValue(
                            json,
                            KnowledgeImportItem[].class
                    );

            if (loaded == null) {
                throw new IllegalStateException(
                        "JSON知识文件内容不能为null"
                );
            }


            return importItems(
                    Arrays.asList(loaded),
                    defaultCategory
            );

        } catch (RuntimeException exception) {


            throw new IllegalStateException(
                    "解析JSON知识文件失败："
                            + filename,
                    exception
            );
        }
    }


    private KnowledgeDocument toDocument(
            KnowledgeImportItem item,
            KnowledgeCategory defaultCategory
    ) {
        if (item == null) {
            throw new IllegalArgumentException(
                    "知识项不能为空"
            );
        }


        if (item.title() == null
                || item.title().isBlank()) {
            throw new IllegalArgumentException(
                    "title不能为空"
            );
        }

        if (item.content() == null
                || item.content().isBlank()) {
            throw new IllegalArgumentException(
                    "content不能为空"
            );
        }


        String documentId =
                item.id() == null
                        || item.id().isBlank()
                        ? UUID.randomUUID().toString()
                        : item.id().strip();


        KnowledgeCategory category =
                item.category() != null
                        ? item.category()
                        : resolvedDefaultCategory(
                        defaultCategory
                );


        String title =
                item.title().strip();

        String content =
                item.content().strip();


        List<String> keywords =
                item.keywords().isEmpty()
                        ? extractKeywords(
                        title,
                        content
                )
                        : item.keywords();

        return new KnowledgeDocument(
                documentId,
                title,
                category,
                content,
                keywords
        );
    }


    private List<String> extractKeywords(
            String title,
            String content
    ) {

        Set<String> keywords =
                new LinkedHashSet<>();


        String searchable =
                (title + " " + content)
                        .toLowerCase(
                                Locale.ROOT
                        );

        for (String term : DOMAIN_TERMS) {
            if (searchable.contains(
                    term.toLowerCase(
                            Locale.ROOT
                    )
            )) {
                keywords.add(term);
            }
        }

        if (!title.isBlank()) {

            keywords.add(
                    shorten(title, 30)
            );
        }

        if (keywords.isEmpty()
                && !content.isBlank()) {
            keywords.add(
                    shorten(content, 12)
            );
        }

        return keywords.stream()
                .limit(10)
                .toList();
    }


    private String decodeUtf8(byte[] bytes) {


        String content =
                new String(
                        bytes,
                        StandardCharsets.UTF_8
                ).strip();


        if (content.isBlank()) {

            throw new IllegalArgumentException(
                    "知识文件内容不能为空"
            );
        }

        return content;
    }


    private KnowledgeCategory resolvedDefaultCategory(
            KnowledgeCategory category
    ) {
        return category == null
                ? KnowledgeCategory.GENERAL
                : category;
    }


    private String extension(String filename) {
        int dot =
                filename.lastIndexOf('.');

        if (dot < 0
                || dot == filename.length() - 1) {
            return "";
        }

        return filename.substring(dot + 1)
                .toLowerCase(Locale.ROOT);
    }


    private String baseName(String filename) {
        int dot =
                filename.lastIndexOf('.');

        String value =
                dot > 0
                        ? filename.substring(0, dot)
                        : filename;

        value = value.strip();

        return value.isBlank()
                ? "导入知识"
                : value;
    }


    private String shorten(
            String value,
            int maximumLength
    ) {
        String stripped =
                value.strip();

        if (stripped.length() <= maximumLength) {
            return stripped;
        }

        return stripped.substring(
                0,
                maximumLength
        );
    }


    private String errorMessage(
            int index,
            KnowledgeImportItem item,
            RuntimeException exception
    ) {
        String identity;

        if (item == null) {
            identity = "null";
        } else if (item.id() != null
                && !item.id().isBlank()) {
            identity = item.id().strip();
        } else if (item.title() != null
                && !item.title().isBlank()) {
            identity = item.title().strip();
        } else {
            identity = "未命名";
        }


        String message =
                exception.getMessage() == null
                        || exception.getMessage().isBlank()
                        ? exception.getClass()
                        .getSimpleName()
                        : exception.getMessage();

        return "第"
                + (index + 1)
                + "项（"
                + identity
                + "）导入失败："
                + message;
    }
}
