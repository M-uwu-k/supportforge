package com.supportforge.memory;

import com.supportforge.persistence.port.ConversationMemoryPersistencePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;


@Component
@ConditionalOnProperty(
        prefix = "supportforge.persistence",
        name = "memory",
        havingValue = "json",
        matchIfMissing = true
)
public class InMemoryConversationMemory
        implements ConversationMemory {


    private final ConversationMemoryPersistencePort store;


    private volatile MemorySnapshot snapshot;


    private final ConversationSummarizer summarizer;


    private final RelevantHistoryRetriever relevantHistoryRetriever;


    private final int compressionBatchSize;


    private final UserProfileExtractor userProfileExtractor;


    public InMemoryConversationMemory(
            ConversationMemoryPersistencePort store
    ) {
        this(
                store,
                ConversationSummarizer.local(),
                RelevantHistoryRetriever.defaults(),
                UserProfileExtractor.defaults(),
                1
        );
    }


    public InMemoryConversationMemory(
            ConversationMemoryPersistencePort store,
            ConversationSummarizer summarizer,
            int compressionBatchSize
    ) {
        this(
                store,
                summarizer,
                RelevantHistoryRetriever.defaults(),
                UserProfileExtractor.defaults(),
                compressionBatchSize
        );
    }


    public InMemoryConversationMemory(
            ConversationMemoryPersistencePort store,
            ConversationSummarizer summarizer,
            RelevantHistoryRetriever relevantHistoryRetriever,
            int compressionBatchSize
    ) {
        this(
                store,
                summarizer,
                relevantHistoryRetriever,
                UserProfileExtractor.defaults(),
                compressionBatchSize
        );
    }


    @Autowired
    public InMemoryConversationMemory(
            ConversationMemoryPersistencePort store,
            ConversationSummarizer summarizer,
            RelevantHistoryRetriever relevantHistoryRetriever,
            UserProfileExtractor userProfileExtractor,

            @Value(
                    "${supportforge.memory.compression-batch-size:4}"
            )
            int compressionBatchSize
    ) {
        if (compressionBatchSize < 1) {
            throw new IllegalArgumentException(
                    "记忆压缩批次数量必须大于0"
            );
        }

        if (compressionBatchSize
                > store.maxMessages()) {
            throw new IllegalArgumentException(
                    "记忆压缩批次数量不能超过最近消息上限"
            );
        }

        this.store = store;
        this.summarizer = summarizer;
        this.relevantHistoryRetriever =
                relevantHistoryRetriever;
        this.userProfileExtractor =
                userProfileExtractor;
        this.compressionBatchSize =
                compressionBatchSize;

        this.snapshot = store.load();
    }

    @Override


    public synchronized void append(
            String userId,
            String conversationId,
            MemoryMessage message
    ) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "记忆消息不能为空"
            );
        }


        ConversationKey key =
                new ConversationKey(
                        userId,
                        conversationId
                );


        Map<ConversationKey, MemoryConversationState>
                conversations =
                indexByKey(snapshot);


        MemoryConversationState previous =
                conversations.remove(key);


        Map<String, String> savedProfile =
                snapshot.userProfiles()
                        .getOrDefault(
                                key.userId(),
                                previous == null
                                        ? Map.of()
                                        : previous.userProfile()
                        );


        Map<String, String> updatedProfile =
                message.role() == MemoryRole.USER
                        ? userProfileExtractor.update(
                        savedProfile,
                        message.content()
                )
                        : savedProfile;


        Map<String, Map<String, String>>
                userProfiles =
                new LinkedHashMap<>(
                        snapshot.userProfiles()
                );

        if (!updatedProfile.isEmpty()) {

            userProfiles.remove(key.userId());
            userProfiles.put(
                    key.userId(),
                    updatedProfile
            );
        }


        List<MemoryMessage> messages =
                new ArrayList<>();

        if (previous != null) {
            messages.addAll(
                    previous.recentMessages()
            );
        }

        messages.add(message);


        String summary =
                previous == null
                        ? ""
                        : previous.summary();


        if (messages.size()
                > store.maxMessages()) {


            int compressCount =
                    Math.min(
                            compressionBatchSize,
                            messages.size()
                    );


            List<MemoryMessage> messagesToCompress =
                    List.copyOf(
                            messages.subList(
                                    0,
                                    compressCount
                            )
                    );


            summary =
                    summarizer.summarize(
                            summary,
                            messagesToCompress
                    );


            messages =
                    new ArrayList<>(
                            messages.subList(
                                    compressCount,
                                    messages.size()
                            )
                    );
        }


        MemoryConversationState nextState =
                new MemoryConversationState(
                        key.userId(),
                        key.conversationId(),
                        messages,
                        summary,
                        updatedProfile,
                        Instant.now()
                );


        conversations.put(
                key,
                nextState
        );


        MemorySnapshot nextSnapshot =
                store.normalize(
                                new MemorySnapshot(
                                        List.copyOf(
                                                conversations.values()
                                        ),
                                        userProfiles
                                )
                );


        store.save(nextSnapshot);
        snapshot = nextSnapshot;
    }

    @Override


    public MemoryContext getContext(
            String userId,
            String conversationId,
            String query
    ) {

        ConversationKey key =
                new ConversationKey(
                        userId,
                        conversationId
                );


        MemorySnapshot current = snapshot;


        MemoryConversationState state =
                findState(
                        current,
                        key
                );


        Map<String, String> savedProfile =
                current.userProfiles()
                        .getOrDefault(
                                key.userId(),
                                state == null
                                        ? Map.of()
                                        : state.userProfile()
                        );


        Map<String, String> effectiveProfile =
                userProfileExtractor.update(
                        savedProfile,
                        query
                );


        List<String> relevantHistory =
                relevantHistoryRetriever.retrieve(
                        current,
                        key.userId(),
                        key.conversationId(),
                        query
                );

        if (state == null) {

            return new MemoryContext(
                    List.of(),
                    "",
                    relevantHistory,
                    effectiveProfile
            );
        }


        return new MemoryContext(
                state.recentMessages(),
                state.summary(),
                relevantHistory,
                effectiveProfile
        );
    }

    @Override


    public synchronized void clear(
            String userId,
            String conversationId
    ) {

        ConversationKey key =
                new ConversationKey(
                        userId,
                        conversationId
                );


        Map<ConversationKey, MemoryConversationState>
                conversations =
                indexByKey(snapshot);


        MemoryConversationState removed =
                conversations.remove(key);


        if (removed == null) {
            return;
        }


        MemorySnapshot nextSnapshot =
                store.normalize(
                        new MemorySnapshot(
                                List.copyOf(
                                        conversations.values()
                                ),
                                snapshot.userProfiles()
                        )
                );


        store.save(nextSnapshot);
        snapshot = nextSnapshot;
    }


    private Map<ConversationKey, MemoryConversationState>
    indexByKey(
            MemorySnapshot source
    ) {

        Map<ConversationKey, MemoryConversationState> result =
                new LinkedHashMap<>();


        for (MemoryConversationState state :
                source.conversations()) {


            ConversationKey key =
                    new ConversationKey(
                            state.userId(),
                            state.conversationId()
                    );


            result.remove(key);
            result.put(
                    key,
                    state
            );
        }

        return result;
    }


    private MemoryConversationState findState(
            MemorySnapshot source,
            ConversationKey expectedKey
    ) {

        for (MemoryConversationState state :
                source.conversations()) {


            ConversationKey actualKey =
                    new ConversationKey(
                            state.userId(),
                            state.conversationId()
                    );

            if (actualKey.equals(expectedKey)) {
                return state;
            }
        }

        return null;
    }
}
