package com.supportforge.objectstorage;

import com.supportforge.config.ObjectStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import com.supportforge.security.EnterpriseRole;
import com.supportforge.security.RequestIdentity;
import com.supportforge.security.TenantContext;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemObjectStorageAdapterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesByChecksumAndReplaysIdempotently() {
        FileSystemObjectStorageAdapter adapter = adapter();
        byte[] content = "原始知识文件".getBytes(StandardCharsets.UTF_8);

        StoredObject first = adapter.storeRawKnowledge(
                "../退款 规则.md",
                "text/markdown; charset=UTF-8",
                content
        );
        StoredObject replay = adapter.storeRawKnowledge(
                "../退款 规则.md",
                "text/markdown; charset=UTF-8",
                content
        );

        assertEquals(first, replay);
        assertEquals(64, first.checksumSha256().length());
        assertTrue(first.objectKey().contains(first.checksumSha256()));
        assertTrue(adapter.exists(first.objectKey()));
        assertArrayEquals(content, adapter.read(first.objectKey()));
    }

    @Test
    void rejectsPathTraversalObjectKey() {
        assertThrows(
                IllegalArgumentException.class,
                () -> adapter().read("../../outside.txt")
        );
    }

    @Test
    void identicalRawFilesUseTheActualRequestTenantDirectory() {
        FileSystemObjectStorageAdapter adapter = adapter();
        byte[] content = "同名原始知识".getBytes(StandardCharsets.UTF_8);
        RequestIdentity a = new RequestIdentity(UUID.randomUUID(), "editor", Set.of(EnterpriseRole.ADMIN), "a");
        RequestIdentity b = new RequestIdentity(UUID.randomUUID(), "editor", Set.of(EnterpriseRole.ADMIN), "b");
        StoredObject first = TenantContext.callWith(a, () -> adapter.storeRawKnowledge("same.md", "text/markdown", content));
        StoredObject second = TenantContext.callWith(b, () -> adapter.storeRawKnowledge("same.md", "text/markdown", content));
        assertTrue(first.objectKey().startsWith("tenants/" + a.tenantId() + "/"));
        assertTrue(second.objectKey().startsWith("tenants/" + b.tenantId() + "/"));
        assertNotEquals(first.objectKey(), second.objectKey());
        assertArrayEquals(content, adapter.read(first.objectKey()));
        assertArrayEquals(content, adapter.read(second.objectKey()));
    }

    private FileSystemObjectStorageAdapter adapter() {
        return new FileSystemObjectStorageAdapter(
                new ObjectStorageProperties(
                        "filesystem",
                        "",
                        "",
                        "",
                        "test-bucket",
                        "us-east-1",
                        true,
                        temporaryDirectory.toString()
                ),
                "00000000-0000-0000-0000-000000000001"
        );
    }
}
