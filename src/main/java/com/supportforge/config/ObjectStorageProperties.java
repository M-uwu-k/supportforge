package com.supportforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Locale;


@ConfigurationProperties(prefix = "supportforge.object-storage")
public record ObjectStorageProperties(
        String provider,
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        String region,
        boolean autoCreateBucket,
        String filesystemRoot
) {

    public ObjectStorageProperties {
        provider = normalize(provider).toLowerCase(Locale.ROOT);
        endpoint = normalize(endpoint);
        accessKey = normalize(accessKey);
        secretKey = secretKey == null ? "" : secretKey;
        bucket = normalize(bucket);
        region = normalize(region);
        filesystemRoot = normalize(filesystemRoot);

        if (provider.isEmpty()) {
            provider = "filesystem";
        }
        if (!provider.equals("filesystem") && !provider.equals("s3")) {
            throw new IllegalArgumentException(
                    "对象存储provider只支持filesystem或s3：" + provider
            );
        }
        if (bucket.isEmpty()) {
            bucket = "supportforge-knowledge";
        }
        if (region.isEmpty()) {
            region = "us-east-1";
        }
        if (filesystemRoot.isEmpty()) {
            filesystemRoot = "data/object-storage";
        }
    }

    public void validateForS3() {
        if (!provider.equals("s3")) {
            throw new IllegalStateException(
                    "当前对象存储provider不是s3"
            );
        }
        if (endpoint.isEmpty()) {
            throw new IllegalStateException(
                    "已启用S3对象存储，但SUPPORTFORGE_OBJECT_STORAGE_ENDPOINT未配置"
            );
        }
        if (accessKey.isEmpty()) {
            throw new IllegalStateException(
                    "已启用S3对象存储，但SUPPORTFORGE_OBJECT_STORAGE_ACCESS_KEY未配置"
            );
        }
        if (secretKey.isEmpty()) {
            throw new IllegalStateException(
                    "已启用S3对象存储，但SUPPORTFORGE_OBJECT_STORAGE_SECRET_KEY未配置"
            );
        }
    }

    public Path filesystemRootPath() {
        return Path.of(filesystemRoot).toAbsolutePath().normalize();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
