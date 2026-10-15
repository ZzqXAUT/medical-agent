package com.example.mednav.auth;

import java.time.Instant;

public record UserAccount(
        String id,
        String username,
        String canonicalUsername,
        String passwordHash,
        String accountStatus,
        Instant createdAt,
        Instant updatedAt) {}
