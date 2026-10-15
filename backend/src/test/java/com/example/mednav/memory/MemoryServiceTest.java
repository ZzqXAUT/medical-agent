package com.example.mednav.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MemoryServiceTest {

    @Test
    void preservesRawClinicalInputAndClearsStaleHypothesesWhenModelFails() {
        InMemoryMemoryCache cache = new InMemoryMemoryCache();
        MemoryService service = new MemoryService(
                cache, mock(MemoryPersistenceService.class), Duration.ofMinutes(10));
        cache.putSession(SessionWorkingMemory.empty("session-1", "user-1"));

        var preserved = service.preserveClinicalInput(
                "user-1", "session-1", "Severe headache for two hours with vomiting");

        assertThat(preserved.narrative())
                .isEqualTo("Severe headache for two hours with vomiting");
        SessionWorkingMemory stored = cache.getSession("session-1").orElseThrow();
        assertThat(stored.symptoms()).isEqualTo(preserved);
        assertThat(stored.preliminaryDiseases()).isEmpty();
    }
}
