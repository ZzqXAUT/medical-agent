package com.example.mednav.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis is a disposable cache. Database rows remain the only long-term source of truth. */
@Component
@ConditionalOnProperty(
        name = "mednav.memory.cache",
        havingValue = "redis",
        matchIfMissing = true)
public class RedisMemoryCache implements MemoryCache {

    private static final String USER_PREFIX = "memory:user:";
    private static final String SESSION_PREFIX = "memory:session:";

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisMemoryCache(ReactiveStringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<UserMemorySnapshot> getUser(String userId) {
        return read(USER_PREFIX + userId, UserMemorySnapshot.class);
    }

    @Override
    public void putUser(UserMemorySnapshot memory, Duration ttl) {
        Boolean stored;
        try {
            stored = redis.opsForValue()
                    .set(USER_PREFIX + memory.userId(), write(memory), ttl)
                    .block();
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (!Boolean.TRUE.equals(stored)) {
            throw unavailable(null);
        }
    }

    @Override
    public Optional<SessionWorkingMemory> getSession(String sessionId) {
        return read(SESSION_PREFIX + sessionId, SessionWorkingMemory.class);
    }

    @Override
    public void putSession(SessionWorkingMemory memory) {
        Boolean stored;
        try {
            stored = redis.opsForValue()
                    .set(SESSION_PREFIX + memory.sessionId(), write(memory))
                    .block();
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (!Boolean.TRUE.equals(stored)) {
            throw unavailable(null);
        }
    }

    @Override
    public void deleteSession(String sessionId) {
        try {
            redis.delete(SESSION_PREFIX + sessionId).block();
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private <T> Optional<T> read(String key, Class<T> type) {
        final String json;
        try {
            json = redis.opsForValue().get(key).block();
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, type));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cached memory JSON is invalid", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Memory cannot be serialized", exception);
        }
    }

    private MemoryCacheUnavailableException unavailable(Throwable cause) {
        return new MemoryCacheUnavailableException(cause);
    }
}
