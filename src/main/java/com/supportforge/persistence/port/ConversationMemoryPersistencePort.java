package com.supportforge.persistence.port;

import com.supportforge.memory.MemorySnapshot;


public interface ConversationMemoryPersistencePort {

    MemorySnapshot load();

    void save(MemorySnapshot snapshot);

    MemorySnapshot normalize(MemorySnapshot snapshot);

    int maxMessages();
}
