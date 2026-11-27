package com.supportforge.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObjectStoragePropertiesTest {

    @Test
    void suppliesSafeFilesystemDefaults() {
        ObjectStorageProperties properties = new ObjectStorageProperties(
                null, null, null, null,
                null, null, true, null
        );

        assertEquals("filesystem", properties.provider());
        assertEquals("supportforge-knowledge", properties.bucket());
        assertEquals("us-east-1", properties.region());
        assertEquals("data/object-storage", properties.filesystemRoot());
    }

    @Test
    void rejectsIncompleteS3Configuration() {
        ObjectStorageProperties properties = new ObjectStorageProperties(
                "s3", "", "", "",
                "bucket", "us-east-1", true, "data"
        );

        assertThrows(
                IllegalStateException.class,
                properties::validateForS3
        );
    }
}
