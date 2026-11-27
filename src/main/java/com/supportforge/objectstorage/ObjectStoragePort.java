package com.supportforge.objectstorage;

/**
 * 原始文件对象存储端口。业务导入层只依赖本接口，不感知文件系统、MinIO或AWS S3。
 */
public interface ObjectStoragePort {

    StoredObject storeRawKnowledge(
            String originalFilename,
            String contentType,
            byte[] content
    );

    byte[] read(String objectKey);

    boolean exists(String objectKey);
}
