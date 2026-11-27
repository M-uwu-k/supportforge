package com.supportforge.objectstorage;

import com.supportforge.config.FeatureFlagProperties;
import com.supportforge.config.ObjectStorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Map;

/**
 * S3 兼容对象存储 Adapter。MinIO Java SDK 负责 Signature V4、连接和协议细节。
 */
@Component
@ConditionalOnProperty(
        prefix = "supportforge.object-storage",
        name = "provider",
        havingValue = "s3"
)
public class S3ObjectStorageAdapter
        implements ObjectStoragePort {

    private final MinioClient client;
    private final String bucket;
    private final String tenantId;

    public S3ObjectStorageAdapter(
            ObjectStorageProperties properties,
            FeatureFlagProperties featureFlags,
            @org.springframework.beans.factory.annotation.Value(
                    "${supportforge.persistence.default-tenant-id:00000000-0000-0000-0000-000000000001}"
            )
            String tenantId
    ) {
        if (!featureFlags.objectStorage()) {
            throw new IllegalStateException(
                    "provider=s3时必须启用supportforge.features.object-storage"
            );
        }
        properties.validateForS3();
        this.bucket = properties.bucket();
        this.tenantId = tenantId;
        this.client = MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(
                        properties.accessKey(),
                        properties.secretKey()
                )
                .region(properties.region())
                .build();
        ensureBucket(properties.autoCreateBucket());
    }

    @Override
    public StoredObject storeRawKnowledge(
            String originalFilename,
            String contentType,
            byte[] content
    ) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("原始文件名不能为空");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("原始文件内容不能为空");
        }
        String checksum = ObjectStorageKeys.sha256(content);
        String objectKey = ObjectStorageKeys.rawKnowledgeKey(

                com.supportforge.security.TenantContext.current()
                        .map(identity -> identity.tenantId().toString()).orElse(tenantId),
                originalFilename,
                checksum
        );
        try {
            StatObjectResponse existing = statOrNull(objectKey);
            if (existing != null) {
                if (existing.size() != content.length) {
                    throw new IllegalStateException(
                            "对象键已存在但大小不一致：" + objectKey
                    );
                }
                return stored(
                        objectKey,
                        checksum,
                        existing.size(),
                        contentType,
                        originalFilename
                );
            }

            client.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .contentType(contentType)
                            .userMetadata(
                                    Map.of(
                                            "sha256", checksum,
                                            "original-filename",
                                            originalFilename
                                    )
                            )
                            .stream(
                                    new ByteArrayInputStream(content),
                                    content.length,
                                    -1
                            )
                            .build()
            );
            return stored(
                    objectKey,
                    checksum,
                    content.length,
                    contentType,
                    originalFilename
            );
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "保存S3原始知识文件失败：" + objectKey,
                    exception
            );
        }
    }

    @Override
    public byte[] read(String objectKey) {
        try (GetObjectResponse response = client.getObject(
                GetObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .build()
        )) {
            return response.readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "读取S3对象失败：" + objectKey,
                    exception
            );
        }
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            return statOrNull(objectKey) != null;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "检查S3对象失败：" + objectKey,
                    exception
            );
        }
    }

    private StatObjectResponse statOrNull(
            String objectKey
    ) throws Exception {
        try {
            return client.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .build()
            );
        } catch (ErrorResponseException exception) {
            String code = exception.errorResponse().code();
            if ("NoSuchKey".equals(code)
                    || "NoSuchObject".equals(code)
                    || "NotFound".equals(code)) {
                return null;
            }
            throw exception;
        }
    }

    private void ensureBucket(boolean autoCreateBucket) {
        try {
            boolean exists = client.bucketExists(
                    BucketExistsArgs.builder()
                            .bucket(bucket)
                            .build()
            );
            if (exists) {
                return;
            }
            if (!autoCreateBucket) {
                throw new IllegalStateException(
                        "对象存储bucket不存在且禁止自动创建：" + bucket
                );
            }
            client.makeBucket(
                    MakeBucketArgs.builder()
                            .bucket(bucket)
                            .build()
            );
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "初始化对象存储bucket失败：" + bucket,
                    exception
            );
        }
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
}
