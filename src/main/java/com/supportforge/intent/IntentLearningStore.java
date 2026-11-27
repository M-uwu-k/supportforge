package com.supportforge.intent;

import com.supportforge.persistence.port.IntentLearningPersistencePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


@Component
@ConditionalOnProperty(
        prefix = "supportforge.persistence",
        name = "intent-learning",
        havingValue = "json",
        matchIfMissing = true
)
public class IntentLearningStore
        implements IntentLearningPersistencePort {

    private final ObjectMapper objectMapper;
    private final Path storagePath;
    private final int maxSamples;


    private List<IntentLearningSample> snapshot;


    public IntentLearningStore(
            ObjectMapper objectMapper,

            @Value(
                    "${supportforge.intent.learning.storage-path:"
                            + "data/intent-learning.json}"
            )
            String storagePath,

            @Value(
                    "${supportforge.intent.learning.max-samples:1000}"
            )
            int maxSamples
    ) {

        if (maxSamples < 1) {
            throw new IllegalArgumentException(
                    "意图学习样本最大数量必须大于0"
            );
        }

        this.objectMapper = objectMapper;


        this.storagePath =
                Path.of(storagePath)
                        .toAbsolutePath()
                        .normalize();
        this.maxSamples = maxSamples;


        this.snapshot = load();
    }


    public synchronized List<IntentLearningSample> findAll() {
        return snapshot;
    }


    public synchronized boolean upsert(
            String message,
            IntentCategory correctIntent
    ) {

        IntentLearningSample sample =
                new IntentLearningSample(
                        message,
                        correctIntent
                );


        String key =
                sampleKey(sample.message());


        Map<String, IntentLearningSample> byMessage =
                toMap(snapshot);


        IntentLearningSample existing =
                byMessage.get(key);


        if (existing != null
                && existing.category()
                == sample.category()) {

            return false;
        }


        byMessage.remove(key);
        byMessage.put(key, sample);


        trimToLimit(byMessage);


        List<IntentLearningSample> next =
                List.copyOf(
                        byMessage.values()
                );


        save(next);
        snapshot = next;

        return true;
    }

    /**
     * 从正式JSON文件恢复并整理样本；仅构造时调用，不是每次findAll/upsert都重新读文件。
     * 去重和裁剪在内存中进行，此处不把整理结果重新写回磁盘；下一次真正变化的upsert会保存整理后的整份列表。
     */
    private List<IntentLearningSample> load() {
        try {

            if (Files.notExists(storagePath)
                    || Files.size(storagePath) == 0) {

                return List.of();
            }


            IntentLearningSample[] loaded =
                    objectMapper.readValue(
                            storagePath,
                            IntentLearningSample[].class
                    );


            if (loaded == null) {
                return List.of();
            }


            Map<String, IntentLearningSample> normalized =
                    toMap(
                            Arrays.asList(loaded)
                    );

            trimToLimit(normalized);

            return List.copyOf(
                    normalized.values()
            );

        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException(
                    "读取意图学习样本文件失败："
                            + storagePath,
                    exception
            );
        }
    }


    private void save(
            List<IntentLearningSample> samples
    ) {
        try {

            Path parent =
                    storagePath.getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }


            Path temporaryPath =
                    storagePath.resolveSibling(
                            storagePath.getFileName()
                                    + ".tmp"
                    );


            objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(
                            temporaryPath,
                            samples
                    );

            try {


                Files.move(
                        temporaryPath,
                        storagePath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (
                    AtomicMoveNotSupportedException exception
            ) {

                Files.move(
                        temporaryPath,
                        storagePath,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException(
                    "保存意图学习样本文件失败："
                            + storagePath,
                    exception
            );
        }
    }


    private Map<String, IntentLearningSample> toMap(
            List<IntentLearningSample> samples
    ) {
        Map<String, IntentLearningSample> result =
                new LinkedHashMap<>();

        for (IntentLearningSample sample : samples) {

            if (sample == null) {
                throw new IllegalArgumentException(
                        "意图学习样本列表不能包含null"
                );
            }

            String key =
                    sampleKey(sample.message());

            result.remove(key);
            result.put(key, sample);
        }

        return result;
    }


    private void trimToLimit(
            Map<String, IntentLearningSample> samples
    ) {
        Iterator<String> iterator =
                samples.keySet().iterator();

        while (samples.size() > maxSamples
                && iterator.hasNext()) {

            iterator.next();
            iterator.remove();
        }
    }

    /**
     * 首尾空格和英文大小写不同视为同一条消息；Locale.ROOT使英文规范化不依赖机器默认地区。
     * 只算查重键，不修改样本message字段；内部空格/标点保留，所以"申请退款"与"申请退款！"仍是不同键。
     */
    private String sampleKey(String message) {
        return message.strip()
                .toLowerCase(Locale.ROOT);
    }


    public Path storagePath() {
        return storagePath;
    }


    public int maxSamples() {
        return maxSamples;
    }
}
