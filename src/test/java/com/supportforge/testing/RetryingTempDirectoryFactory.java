package com.supportforge.testing;

import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;


public final class RetryingTempDirectoryFactory
        implements TempDirFactory {

    private static final int MAX_ATTEMPTS = 20;
    private static final long RETRY_DELAY_MILLIS = 25L;

    private Path directory;

    @Override
    public Path createTempDirectory(
            AnnotatedElementContext elementContext,
            ExtensionContext extensionContext
    ) throws IOException {
        Path root = Path.of("target", "junit-temp")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(root);
        directory = Files.createTempDirectory(root, "junit-");
        return directory;
    }

    @Override
    public void close() throws IOException {
        if (directory == null) {
            return;
        }

        IOException lastFailure = null;
        for (int attempt = 1;
             attempt <= MAX_ATTEMPTS;
             attempt++) {
            try {
                deleteRecursively(directory);
                return;
            } catch (IOException exception) {
                lastFailure = exception;
                if (attempt < MAX_ATTEMPTS) {
                    pauseBeforeRetry();
                }
            }
        }

        throw new IOException(
                "重试后仍无法清理JUnit临时目录：" + directory,
                lastFailure
        );
    }

    private static void deleteRecursively(Path root)
            throws IOException {
        if (Files.notExists(root)) {
            return;
        }

        Files.walkFileTree(
                root,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(
                            Path file,
                            BasicFileAttributes attributes
                    ) throws IOException {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(
                            Path directory,
                            IOException exception
                    ) throws IOException {
                        if (exception != null) {
                            throw exception;
                        }
                        Files.deleteIfExists(directory);
                        return FileVisitResult.CONTINUE;
                    }
                }
        );
    }

    private static void pauseBeforeRetry() throws IOException {
        try {
            Thread.sleep(RETRY_DELAY_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "清理JUnit临时目录时线程被中断",
                    exception
            );
        }
    }
}
