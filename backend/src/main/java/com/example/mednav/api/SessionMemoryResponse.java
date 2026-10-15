package com.example.mednav.api;

import com.example.mednav.clinical.SymptomCase;
import com.example.mednav.memory.SessionMemoryRecord;
import java.time.Instant;
import java.util.List;

public record SessionMemoryResponse(
        String sessionId,
        SymptomCase symptoms,
        List<String> preliminaryDiseases,
        String summary,
        Instant createdAt) {

    public static SessionMemoryResponse from(SessionMemoryRecord memory) {
        return new SessionMemoryResponse(
                memory.sessionId(),
                memory.symptoms(),
                memory.preliminaryDiseases(),
                memory.summary(),
                memory.createdAt());
    }
}
