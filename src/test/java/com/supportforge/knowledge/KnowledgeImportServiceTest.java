package com.supportforge.knowledge;

import com.supportforge.config.KnowledgeIngestionProperties;
import com.supportforge.knowledge.ingestion.KnowledgeContentCleaner;
import com.supportforge.knowledge.ingestion.KnowledgeIngestionPipeline;
import com.supportforge.knowledge.ingestion.NoOpKnowledgeIngestionAdapter;
import com.supportforge.knowledge.ingestion.StableKnowledgeChunker;
import com.supportforge.objectstorage.ObjectStoragePort;
import com.supportforge.objectstorage.StoredObject;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class KnowledgeImportServiceTest {


    @Test
    void shouldImportBatchAndGenerateMissingIdsAndKeywords() {
        InMemoryKnowledgeBase knowledgeBase =
                new InMemoryKnowledgeBase();

        KnowledgeImportService service =
                service(knowledgeBase, 100_000);

        int before = knowledgeBase.count();

        KnowledgeImportResult result = service.importItems(
                List.of(
                        new KnowledgeImportItem(
                                null,
                                "会员等级规则",
                                KnowledgeCategory.GENERAL,
                                "会员消费后可以获得积分。",
                                List.of()
                        ),
                        new KnowledgeImportItem(
                                "batch-refund",
                                "批量退款规则",
                                KnowledgeCategory.BILLING,
                                "退款需要核验订单和支付记录。",
                                List.of("退款", "订单")
                        )
                ),
                KnowledgeCategory.GENERAL
        );

        assertAll(
                () -> assertEquals(2, result.total()),
                () -> assertEquals(2, result.imported()),
                () -> assertEquals(0, result.failed()),
                () -> assertEquals(before + 2, knowledgeBase.count()),
                () -> assertFalse(
                        result.documents()
                                .getFirst()
                                .id()
                                .isBlank()
                ),
                () -> assertFalse(
                        result.documents()
                                .getFirst()
                                .keywords()
                                .isEmpty()
                )
        );
    }


    @Test
    void shouldUpdateExistingDocumentAndContinueBatch() {
        InMemoryKnowledgeBase knowledgeBase =
                new InMemoryKnowledgeBase();

        knowledgeBase.add(
                new KnowledgeDocument(
                        "duplicate-import",
                        "已有文档",
                        KnowledgeCategory.GENERAL,
                        "已有内容",
                        List.of("已有")
                )
        );

        KnowledgeImportService service =
                service(knowledgeBase, 100_000);

        KnowledgeImportResult result = service.importItems(
                List.of(
                        new KnowledgeImportItem(
                                "duplicate-import",
                                "重复文档",
                                KnowledgeCategory.GENERAL,
                                "重复内容",
                                List.of("重复")
                        ),
                        new KnowledgeImportItem(
                                "valid-import",
                                "有效文档",
                                KnowledgeCategory.GENERAL,
                                "有效内容",
                                List.of("有效")
                        )
                ),
                KnowledgeCategory.GENERAL
        );

        assertEquals(2, result.total());
        assertEquals(2, result.imported());
        assertEquals(0, result.failed());
        assertEquals("duplicate-import",
                result.documents().getFirst().id());
        assertEquals("重复内容",
                result.documents().getFirst().content());
        assertEquals("valid-import",
                result.documents().get(1).id());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void shouldReplayIdenticalImportWithoutDuplicatingDocument() {
        InMemoryKnowledgeBase knowledgeBase =
                new InMemoryKnowledgeBase();
        KnowledgeImportService service =
                service(knowledgeBase, 100_000);
        KnowledgeImportItem item = new KnowledgeImportItem(
                "idempotent-import",
                "幂等导入",
                KnowledgeCategory.GENERAL,
                "同一份内容可以安全重复提交。",
                List.of("幂等")
        );

        int before = knowledgeBase.count();
        KnowledgeImportResult first = service.importItems(
                List.of(item),
                KnowledgeCategory.GENERAL
        );
        KnowledgeImportResult second = service.importItems(
                List.of(item),
                KnowledgeCategory.GENERAL
        );

        assertEquals(1, first.imported());
        assertEquals(1, second.imported());
        assertEquals(0, second.failed());
        assertEquals(before + 1, knowledgeBase.count());
    }


    @Test
    void shouldImportUtf8TextFileAsOneDocument() {
        InMemoryKnowledgeBase knowledgeBase =
                new InMemoryKnowledgeBase();

        KnowledgeImportResult result = service(
                knowledgeBase,
                100_000
        ).importFile(
                "refund-guide.txt",
                "退款到账通常需要几个工作日。"
                        .getBytes(StandardCharsets.UTF_8),
                KnowledgeCategory.BILLING
        );

        KnowledgeDocument document =
                result.documents().getFirst();

        assertEquals(1, result.imported());
        assertEquals("refund-guide", document.title());
        assertEquals(KnowledgeCategory.BILLING, document.category());
        assertTrue(document.content().contains("退款到账"));
    }


    @Test
    void shouldUseFirstMarkdownHeadingAsTitle() {
        KnowledgeImportResult result = service(
                new InMemoryKnowledgeBase(),
                100_000
        ).importFile(
                "technical.md",
                """
                # 登录故障排查

                登录提示401时应检查凭证和访问令牌。
                """.getBytes(StandardCharsets.UTF_8),
                KnowledgeCategory.TECHNICAL
        );

        KnowledgeDocument document =
                result.documents().getFirst();

        assertEquals("登录故障排查", document.title());
        assertEquals(KnowledgeCategory.TECHNICAL, document.category());
    }


    @Test
    void shouldImportJsonArrayWithPerDocumentCategories() {
        KnowledgeImportResult result = service(
                new InMemoryKnowledgeBase(),
                100_000
        ).importFile(
                "knowledge.json",
                """
                [
                  {
                    "id": "json-refund",
                    "title": "JSON退款规则",
                    "category": "BILLING",
                    "content": "退款需要订单号。",
                    "keywords": ["退款", "订单号"]
                  },
                  {
                    "id": "json-login",
                    "title": "JSON登录规则",
                    "category": "TECHNICAL",
                    "content": "登录失败时检查凭证。",
                    "keywords": ["登录失败", "凭证"]
                  }
                ]
                """.getBytes(StandardCharsets.UTF_8),
                KnowledgeCategory.GENERAL
        );

        assertEquals(2, result.imported());
        assertEquals(KnowledgeCategory.BILLING,
                result.documents().get(0).category());
        assertEquals(KnowledgeCategory.TECHNICAL,
                result.documents().get(1).category());
    }


    @Test
    void shouldRejectUnsupportedOversizedAndMalformedFiles() {
        KnowledgeImportService smallService = service(
                new InMemoryKnowledgeBase(),
                10
        );

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> smallService.importFile(
                                "knowledge.pdf",
                                new byte[]{1},
                                KnowledgeCategory.GENERAL
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> smallService.importFile(
                                "large.txt",
                                "12345678901".getBytes(StandardCharsets.UTF_8),
                                KnowledgeCategory.GENERAL
                        )
                ),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> service(
                                new InMemoryKnowledgeBase(),
                                100_000
                        ).importFile(
                                "broken.json",
                                "{not-json}"
                                        .getBytes(StandardCharsets.UTF_8),
                                KnowledgeCategory.GENERAL
                        )
                )
        );
    }

    @Test
    void shouldStoreRawFileAndAttachItToImportedVersion() {
        RecordingKnowledgeBase knowledgeBase =
                new RecordingKnowledgeBase();
        RecordingObjectStorage objectStorage =
                new RecordingObjectStorage();
        KnowledgeImportService service = new KnowledgeImportService(
                knowledgeBase,
                new ObjectMapper(),
                objectStorage,
                pipeline(),
                100_000
        );

        KnowledgeImportResult result = service.importFile(
                "source.md",
                "# 原始文件\n\n正文。".getBytes(StandardCharsets.UTF_8),
                KnowledgeCategory.GENERAL
        );

        assertEquals(1, objectStorage.storeCalls);
        assertEquals("source.md", objectStorage.lastFilename);
        assertEquals("text/markdown; charset=UTF-8",
                objectStorage.lastContentType);
        assertEquals(
                List.of(result.documents().getFirst().id()),
                knowledgeBase.attachedDocumentIds
        );
        assertEquals(objectStorage.storedObject,
                knowledgeBase.attachedSource);
    }


    private KnowledgeImportService service(
            KnowledgeBase knowledgeBase,
            long maximumFileBytes
    ) {
        return new KnowledgeImportService(
                knowledgeBase,
                new ObjectMapper(),
                new RecordingObjectStorage(),
                pipeline(),
                maximumFileBytes
        );
    }

    private KnowledgeIngestionPipeline pipeline() {
        KnowledgeIngestionProperties properties =
                new KnowledgeIngestionProperties(
                        800,
                        120,
                        120,
                        "test-parser-v1",
                        "test-chunker-v1"
                );
        return new KnowledgeIngestionPipeline(
                new KnowledgeContentCleaner(),
                new StableKnowledgeChunker(properties),
                new NoOpKnowledgeIngestionAdapter(),
                properties
        );
    }

    private static final class RecordingObjectStorage
            implements ObjectStoragePort {
        private int storeCalls;
        private String lastFilename;
        private String lastContentType;
        private byte[] content = new byte[0];
        private StoredObject storedObject;

        @Override
        public StoredObject storeRawKnowledge(
                String originalFilename,
                String contentType,
                byte[] content
        ) {
            storeCalls++;
            lastFilename = originalFilename;
            lastContentType = contentType;
            this.content = content.clone();
            storedObject = new StoredObject(
                    "test-bucket",
                    "raw/" + originalFilename,
                    "0".repeat(64),
                    content.length,
                    contentType,
                    originalFilename
            );
            return storedObject;
        }

        @Override
        public byte[] read(String objectKey) {
            return content.clone();
        }

        @Override
        public boolean exists(String objectKey) {
            return storedObject != null;
        }
    }

    private static final class RecordingKnowledgeBase
            extends InMemoryKnowledgeBase {
        private List<String> attachedDocumentIds = List.of();
        private StoredObject attachedSource;

        @Override
        public void attachRawSource(
                List<String> documentIds,
                StoredObject source
        ) {
            attachedDocumentIds = List.copyOf(documentIds);
            attachedSource = source;
        }
    }
}
