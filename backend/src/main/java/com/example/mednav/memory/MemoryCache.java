package com.example.mednav.memory;

import java.time.Duration;
import java.util.Optional;

public interface MemoryCache {

    Optional<UserMemorySnapshot> getUser(String userId);

    void putUser(UserMemorySnapshot memory, Duration ttl);

    Optional<SessionWorkingMemory> getSession(String sessionId);

    void putSession(SessionWorkingMemory memory);

    void deleteSession(String sessionId);
}
