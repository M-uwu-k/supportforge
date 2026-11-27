package com.supportforge.objectstorage;

import com.supportforge.config.ObjectStorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * local/test 的对象存储 Adapter。布局与 S3 object key 一致，便于本地调试和迁移。
 */
@Component
@ConditionalOnProperty(
        prefix = "supportforge.object-storage",
        name = "provider",
        havingValue = "filesystem",
        matchIfMissing = true
)
public class FileSystemObjectStorageAdapter
        implements ObjectStoragePort {

    private final Path root;
    private final String bucket;
    private final String tenantId;

    public FileSystemObjectStorageAdapter(
            ObjectStorageProperties properties,
            @org.springframework.beans.factory.annotation.Value(
                    "${supportforge.persistence.default-tenant-id:00000000-0000-0000-0000-000000000001}"
            )
            String tenantId
    ) {
        this.root = properties.filesystemRootPath();
        this.bucket = properties.bucket();
        this.tenantId = tenantId;
    }

    @Override
    public synchronized StoredObject storeRawKnowledge(
            String originalFilename,
            String contentType,
            byte[] content
    ) {
        validateInput(originalFilename, content);
        String checksum = ObjectStorageKeys.sha256(content);
        String objectKey = ObjectStorageKeys.rawKnowledgeKey(

                com.supportforge.security.TenantContext.current()
                        .map(identity -> identity.tenantId().toString()).orElse(tenantId),
                originalFilename,
                checksum
        );
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                byte[] existing = Files.readAllBytes(target);
                if (!ObjectStorageKeys.sha256(existing).equals(checksum)) {
                    throw new IllegalStateException(
                            "对象键已存在但校验和不一致：" + objectKey
                    );
                }
                return stored(
                        objectKey,
                        checksum,
                        existing.length,
                        contentType,
                        originalFilename
                );
            }

            Path temporary = Files.createTempFile(
                    target.getParent(),
                    ".supportforge-upload-",
                    ".tmp"
            );
            try {
                Files.write(temporary, content);
                moveAtomically(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return stored(
                    objectKey,
                    checksum,
                    content.length,
                    contentType,
                    originalFilename
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "保存本地原始知识文件失败：" + objectKey,
                    exception
            );
        }
    }

    @Override
    public byte[] read(String objectKey) {
        try {
            return Files.readAllBytes(resolve(objectKey));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "读取本地对象失败：" + objectKey,
                    exception
            );
        }
    }

    @Override
    public boolean exists(String objectKey) {
        return Files.isRegularFile(resolve(objectKey));
    }

    private Path resolve(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("对象键不能为空");
        }
        Path bucketRoot = root.resolve(bucket).normalize();
        Path resolved = bucketRoot.resolve(objectKey).normalize();
        if (!resolved.startsWith(bucketRoot)) {
            throw new IllegalArgumentException(
                    "对象键不能越过存储根目录：" + objectKey
            );
        }
        return resolved;
    }

    private StoredObject stored(
            String key,
            String checksum,
            long size,
            String contentType,
            String originalFilename
    ) {
        return new StoredObject(
                bucket,
                key,
                checksum,
                size,
                contentType,
                originalFilename
        );
    }

    private static void validateInput(
            String originalFilename,
            byte[] content
    ) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("原始文件名不能为空");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("原始文件内容不能为空");
        }
    }

    private static void moveAtomically(
            Path source,
            Path target
    ) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }
}
