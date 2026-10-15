package com.example.mednav.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRunRequest(
        @NotBlank(message = "message must not be blank")
        @Size(max = 8_000, message = "message must not exceed 8000 characters")
        String message) {}

