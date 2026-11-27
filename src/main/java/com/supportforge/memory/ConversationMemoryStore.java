package com.supportforge.memory;

import com.supportforge.persistence.port.ConversationMemoryPersistencePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Component
public class ConversationMemoryStore
        implements ConversationMemoryPersistencePort {

    private final ObjectMapper objectMapper;
    private final Path storagePath;
    private final int maxConversations;
    private final int maxMessages;


    public ConversationMemoryStore(
            ObjectMapper objectMapper,


            @Value(
                    "${supportforge.memory.storage-path:"
                            + "data/conversation-memory.json}"
            )
            String storagePath,

            @Value(
                    "${supportforge.memory.max-conversations:1000}"
            )
            int maxConversations,

            @Value(
                    "${supportforge.memory.max-messages:12}"
            )
            int maxMessages
    ) {

        if (maxConversations < 1) {
            throw new IllegalArgumentException(
                    "最大会话数量必须大于0"
            );
        }

        if (maxMessages < 1) {
            throw new IllegalArgumentException(
                    "每个会话最大消息数量必须大于0"
            );
        }

        this.objectMapper = objectMapper;


        this.storagePath =
                Path.of(storagePath)
                        .toAbsolutePath()
                        .normalize();

        this.maxConversations = maxConversations;
        this.maxMessages = maxMessages;
    }


    public MemorySnapshot load() {
        try {


            if (Files.notExists(storagePath)
                    || Files.size(storagePath) == 0) {
                return MemorySnapshot.empty();
            }


            MemorySnapshot loaded =
                    objectMapper.readValue(
                            storagePath,
                            MemorySnapshot.class
                    );

            return normalize(loaded);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException(
                    "读取会话记忆文件失败：" + storagePath,
                    exception
            );
        }
    }


    public synchronized void save(
            MemorySnapshot snapshot
    ) {


        MemorySnapshot normalized =
                normalize(snapshot);


        try {
            Path parent = storagePath.getParent();

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
                            normalized
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
                    "保存会话记忆文件失败：" + storagePath,
                    exception
            );
        }

    }


    public MemorySnapshot normalize(
            MemorySnapshot snapshot
    ) {

        if (snapshot == null) {
            return MemorySnapshot.empty();
        }


        Map<ConversationKey, MemoryConversationState>
                conversationsByKey =
                new LinkedHashMap<>();


        for (MemoryConversationState state :
                snapshot.conversations()) {


            ConversationKey key =
                    new ConversationKey(
                            state.userId(),
                            state.conversationId()
                    );


            List<MemoryMessage> allMessages =
                    state.recentMessages();


            int messageFromIndex =
                    Math.max(
                            0,
                            allMessages.size() - maxMessages
                    );


            List<MemoryMessage> retainedMessages =
                    List.copyOf(
                            allMessages.subList(
                                    messageFromIndex,
                                    allMessages.size()
                            )
                    );


            MemoryConversationState normalizedState =
                    new MemoryConversationState(
                            key.userId(),
                            key.conversationId(),
                            retainedMessages,
                            state.summary(),
                            state.userProfile(),
                            state.updatedAt()
                    );


            conversationsByKey.remove(key);
            conversationsByKey.put(
                    key,
                    normalizedState
            );
        }


        List<MemoryConversationState> allConversations =
                List.copyOf(
                        conversationsByKey.values()
                );


        int conversationFromIndex =
                Math.max(
                        0,
                        allConversations.size()
                                - maxConversations
                );


        List<MemoryConversationState> retainedConversations =
                List.copyOf(
                        allConversations.subList(
                                conversationFromIndex,
                                allConversations.size()
                        )
                );

        Map<String, Map<String, String>> normalizedProfiles =
                normalizeUserProfiles(
                        snapshot,
                        allConversations
                );


        return new MemorySnapshot(
                retainedConversations,
                normalizedProfiles
        );
    }


    private Map<String, Map<String, String>> normalizeUserProfiles(
            MemorySnapshot snapshot,
            List<MemoryConversationState> conversations
    ) {


        Map<String, Map<String, String>> profiles =
                new LinkedHashMap<>();


        for (MemoryConversationState state :
                conversations) {

            mergeProfile(
                    profiles,
                    state.userId(),
                    state.userProfile()
            );
        }


        snapshot.userProfiles()
                .forEach((userId, profile) ->
                        mergeProfile(
                                profiles,
                                userId,
                                profile
                        )
                );


        List<Map.Entry<String, Map<String, String>>>
                allProfiles =
                List.copyOf(
                        profiles.entrySet()
                );


        int fromIndex =
                Math.max(
                        0,
                        allProfiles.size()
                                - maxConversations
                );


        Map<String, Map<String, String>> retained =
                new LinkedHashMap<>();


        for (Map.Entry<String, Map<String, String>> entry :
                allProfiles.subList(
                        fromIndex,
                        allProfiles.size()
                )) {

            retained.put(
                    entry.getKey(),
                    entry.getValue()
            );

        }


        return java.util.Collections
                .unmodifiableMap(retained);
    }


    private void mergeProfile(
            Map<String, Map<String, String>> profiles,
            String userId,
            Map<String, String> newProfile
    ) {


        if (newProfile == null
                || newProfile.isEmpty()) {
            return;
        }

        String normalizedUserId =
                userId == null || userId.isBlank()
                        ? "anonymous"
                        : userId.strip();


        Map<String, String> merged =
                new LinkedHashMap<>(
                        profiles.getOrDefault(
                                normalizedUserId,
                                Map.of()
                        )
                );


        merged.putAll(newProfile);


        profiles.remove(normalizedUserId);
        profiles.put(
                normalizedUserId,
                Map.copyOf(merged)
        );
    }


    public Path storagePath() {
        return storagePath;
    }

    public int maxConversations() {
        return maxConversations;
    }

    public int maxMessages() {
        return maxMessages;
    }
}
