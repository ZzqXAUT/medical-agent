package com.example.mednav.memory;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Deterministic cache for tests. Production/local runtime uses Redis. */
@Component
@ConditionalOnProperty(name = "mednav.memory.cache", havingValue = "in-memory")
public class InMemoryMemoryCache implements MemoryCache {

    private final Map<String, UserMemorySnapshot> users = new ConcurrentHashMap<>();
    private final Map<String, SessionWorkingMemory> sessions = new ConcurrentHashMap<>();

    @Override
    public Optional<UserMemorySnapshot> getUser(String userId) {
        return Optional.ofNullable(users.get(userId));
    }

    @Override
    public void putUser(UserMemorySnapshot memory, Duration ttl) {
        users.put(memory.userId(), memory);
    }

    @Override
    public Optional<SessionWorkingMemory> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public void putSession(SessionWorkingMemory memory) {
        sessions.put(memory.sessionId(), memory);
    }

    @Override
    public void deleteSession(String sessionId) {
        sessions.remove(sessionId);
    }
}
