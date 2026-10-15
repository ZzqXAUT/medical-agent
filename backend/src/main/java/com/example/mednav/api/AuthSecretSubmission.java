package com.example.mednav.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record AuthSecretSubmission(
        @NotNull UUID conversationId,
        @NotNull UUID requestId,
        @NotBlank @Size(max = 100) String password) {}
