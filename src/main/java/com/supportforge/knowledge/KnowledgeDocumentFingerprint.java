package com.supportforge.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 与数据库版本校验和共用的完整文档指纹；标题、分类、正文和关键词都参与。 */
public final class KnowledgeDocumentFingerprint {
    private KnowledgeDocumentFingerprint() { }

    public static String sha256(KnowledgeDocument document) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, document.title());
            update(digest, document.category().name());
            update(digest, document.content());
            for (String keyword : document.keywords()) update(digest, keyword);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前JVM不支持SHA-256", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
