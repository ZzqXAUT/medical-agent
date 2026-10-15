package com.example.mednav.profile;

import java.util.List;
import java.util.Map;

public record ProfileExtractionResult(
        Map<String, Object> values,
        List<String> validationErrors,
        boolean skipRemaining) {}
