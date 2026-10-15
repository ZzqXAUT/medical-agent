package com.example.mednav.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ManualLocationRequest(
        @NotNull UUID conversationId,
        @NotBlank @Size(max = 500) String address) {}
