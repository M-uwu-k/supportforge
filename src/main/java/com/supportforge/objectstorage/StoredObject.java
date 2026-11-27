package com.supportforge.objectstorage;

/**
 * 已保存原文件的稳定定位信息。对象键按内容 SHA-256 生成，因此相同文件可安全重放。
 */
public record StoredObject(
        String bucket,
        String objectKey,
        String checksumSha256,
        long sizeBytes,
        String contentType,
        String originalFilename
) {

    public StoredObject {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("对象存储bucket不能为空");
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("对象键不能为空");
        }
        if (checksumSha256 == null
                || !checksumSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("对象SHA-256格式非法");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("对象大小不能为负数");
        }
        contentType = contentType == null || contentType.isBlank()
                ? "application/octet-stream"
                : contentType.strip();
        originalFilename = originalFilename == null
                ? ""
                : originalFilename.strip();
    }
}
