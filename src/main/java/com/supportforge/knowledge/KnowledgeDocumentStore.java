package com.supportforge.knowledge;

import com.supportforge.persistence.port.KnowledgeDocumentPersistencePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;


@Component
@ConditionalOnProperty(
        prefix = "supportforge.persistence",
        name = "knowledge",
        havingValue = "json",
        matchIfMissing = true
)
public class KnowledgeDocumentStore
        implements KnowledgeDocumentPersistencePort {


    private final ObjectMapper objectMapper;

    private final Path storagePath;


    public KnowledgeDocumentStore(
            ObjectMapper objectMapper,


            @Value(
                    "${supportforge.knowledge.storage-path:"
                            + "data/knowledge-documents.json}"
            )
            String storagePath
    ) {
        this.objectMapper = objectMapper;


        this.storagePath = Path.of(storagePath)
                .toAbsolutePath()
                .normalize();
    }


    public List<KnowledgeDocument> load() {
        try {


            if (Files.notExists(storagePath)
                    || Files.size(storagePath) == 0) {
                return List.of();
            }


            KnowledgeDocument[] loadedDocuments =
                    objectMapper.readValue(
                            storagePath,
                            KnowledgeDocument[].class
                    );


            if (loadedDocuments == null) {
                return List.of();
            }


            return List.copyOf(
                    Arrays.asList(loadedDocuments)
            );


        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException(
                    "读取知识库文件失败："
                            + storagePath,
                    exception
            );
        }
    }


    public synchronized void save(
            List<KnowledgeDocument> documents
    ) {
        try {

            Path parent = storagePath.getParent();

            if (parent != null) {

                Files.createDirectories(parent);
            }


            Path temporaryPath =
                    storagePath.resolveSibling(
                            storagePath.getFileName()
                                    + ".tmp"
                    );


            objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(
                            temporaryPath,
                            documents
                    );

            try {


                Files.move(
                        temporaryPath,
                        storagePath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (
                    AtomicMoveNotSupportedException exception
            ) {


                Files.move(
                        temporaryPath,
                        storagePath,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }


        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException(
                    "保存知识库文件失败："
                            + storagePath,
                    exception
            );
        }
    }


    public Path storagePath() {
        return storagePath;
    }
}
