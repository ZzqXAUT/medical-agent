package com.example.mednav.memory;

import com.example.mednav.clinical.SymptomCase;
import java.time.Instant;
import java.util.List;

/** One finalized historical Session. Preliminary diseases remain hypotheses, not diagnoses. */
public record SessionMemoryRecord(
        String sessionId,
        SymptomCase symptoms,
        List<String> preliminaryDiseases,
        String summary,
        Instant createdAt) {

    public SessionMemoryRecord {
        preliminaryDiseases = preliminaryDiseases == null
                ? List.of()
                : List.copyOf(preliminaryDiseases);
        summary = summary == null ? "" : summary;
    }
}
