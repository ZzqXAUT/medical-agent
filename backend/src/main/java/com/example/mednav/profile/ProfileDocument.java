package com.example.mednav.profile;

import java.time.Instant;
import java.util.Map;

public record ProfileDocument(String userId, Map<String, Object> profile, Instant updatedAt) {}
