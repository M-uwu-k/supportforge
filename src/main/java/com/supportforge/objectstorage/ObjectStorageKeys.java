package com.supportforge.objectstorage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 对象内容寻址、校验和及安全键名的无状态工具。 */
final class ObjectStorageKeys {

    private ObjectStorageKeys() {
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(content)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "当前JVM不支持SHA-256",
                    exception
            );
        }
    }

    static String rawKnowledgeKey(
            String tenantId,
            String filename,
            String checksum
    ) {
        String safeFilename = filename
                .replaceAll("[^\\p{L}\\p{N}._-]", "_")
                .replace("..", "_");
        if (safeFilename.isBlank()) {
            safeFilename = "upload.bin";
        }
        return "tenants/"
                + tenantId
                + "/knowledge/raw/"
                + checksum
                + "/"
                + safeFilename;
    }
}
