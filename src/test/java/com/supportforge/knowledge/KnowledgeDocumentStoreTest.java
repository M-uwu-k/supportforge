package com.supportforge.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeDocumentStoreTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldReturnEmptyListWhenStorageFileDoesNotExist() {
        KnowledgeDocumentStore store = storeAt("missing/knowledge.json");

        assertTrue(store.load().isEmpty());
    }

    @Test
    void shouldSaveAndLoadDocuments() {
        Path storagePath = tempDir.resolve("nested/knowledge.json");
        KnowledgeDocumentStore store = new KnowledgeDocumentStore(
                objectMapper,
                storagePath.toString()
        );
        KnowledgeDocument document = dynamicDocument();

        store.save(List.of(document));

        assertTrue(Files.exists(storagePath));
        assertEquals(List.of(document), store.load());
    }

    @Test
    void shouldRestoreDynamicDocumentAfterKnowledgeBaseRestart() {
        KnowledgeDocumentStore store = storeAt("restart/knowledge.json");
        KnowledgeDocument document = dynamicDocument();

        InMemoryKnowledgeBase firstInstance = new InMemoryKnowledgeBase();
        firstInstance.configurePersistence(store);
        firstInstance.add(document);

        InMemoryKnowledgeBase restartedInstance = new InMemoryKnowledgeBase();
        restartedInstance.configurePersistence(store);

        assertEquals(8, restartedInstance.count());
        assertEquals(
                document.id(),
                restartedInstance.search(
                                "重启恢复专用关键词",
                                KnowledgeCategory.TECHNICAL,
                                3
                        )
                        .getFirst()
                        .document()
                        .id()
        );
    }

    @Test
    void shouldNotRestoreDeletedDocumentAfterKnowledgeBaseRestart() {
        KnowledgeDocumentStore store = storeAt("delete/knowledge.json");
        KnowledgeDocument document = dynamicDocument();

        InMemoryKnowledgeBase firstInstance = new InMemoryKnowledgeBase();
        firstInstance.configurePersistence(store);
        firstInstance.add(document);
        firstInstance.delete(document.id());

        InMemoryKnowledgeBase restartedInstance = new InMemoryKnowledgeBase();
        restartedInstance.configurePersistence(store);

        assertEquals(7, restartedInstance.count());
        assertTrue(
                restartedInstance.search(
                                "重启恢复专用关键词",
                                KnowledgeCategory.TECHNICAL,
                                3
                        )
                        .stream()
                        .noneMatch(result ->
                                result.document()
                                        .id()
                                        .equals(document.id())
                        )
        );
        assertTrue(
                store.load()
                        .stream()
                        .noneMatch(saved ->
                                saved.id().equals(document.id())
                        )
        );
    }

    private KnowledgeDocumentStore storeAt(String relativePath) {
        return new KnowledgeDocumentStore(
                objectMapper,
                tempDir.resolve(relativePath).toString()
        );
    }

    private KnowledgeDocument dynamicDocument() {
        return new KnowledgeDocument(
                "runtime-persistence-test",
                "持久化测试知识",
                KnowledgeCategory.TECHNICAL,
                "这篇知识用于验证应用重启后能够恢复运行时添加的数据。",
                List.of("重启恢复专用关键词", "持久化")
        );
    }
}
