package com.supportforge.memory;

import java.util.List;


public interface ConversationMemory {


    void append(
            String userId,
            String conversationId,
            MemoryMessage message
    );


    MemoryContext getContext(
            String userId,
            String conversationId,
            String query
    );


    void clear(
            String userId,
            String conversationId
    );


    default List<MemoryMessage> getRecent(
            String userId,
            String conversationId
    ) {


        return getContext(
                userId,
                conversationId,
                ""
        ).recentMessages();
    }


    default String buildContext(
            String userId,
            String conversationId,
            String query
    ) {

        return getContext(
                userId,
                conversationId,
                query
        ).toPrompt();
    }
}
