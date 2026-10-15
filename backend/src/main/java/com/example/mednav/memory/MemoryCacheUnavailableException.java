package com.example.mednav.memory;

public final class MemoryCacheUnavailableException extends RuntimeException {

    public MemoryCacheUnavailableException(Throwable cause) {
        super("Memory cache is temporarily unavailable", cause);
    }
}
