package com.example.mednav.memory;

import com.example.mednav.clinical.ClinicalDecision;
import com.example.mednav.clinical.DiseaseHypothesis;
import com.example.mednav.clinical.SymptomCase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final MemoryCache cache;
    private final MemoryPersistenceService persistence;
    private final Duration userCacheTtl;

    public MemoryService(
            MemoryCache cache,
            MemoryPersistenceService persistence,
            @Value("${mednav.memory.user-cache-ttl:10m}") Duration userCacheTtl) {
        this.cache = cache;
        this.persistence = persistence;
        this.userCacheTtl = userCacheTtl;
    }

    public UserMemorySnapshot initializeSession(String userId, String sessionId) {
        UserMemorySnapshot user = persistence.loadUser(userId);
        cache.putUser(user, userCacheTtl);
        cache.putSession(SessionWorkingMemory.empty(sessionId, userId));
        log.info(
                "memory_session_initialized sessionId={} hasLongTermProfile={} historyCount={}",
                sessionId,
                user.hasLongTermProfile(),
                user.history().size());
        return user;
    }

    public EffectiveMemory effective(String userId, String sessionId) {
        UserMemorySnapshot user = user(userId);
        SessionWorkingMemory session = session(sessionId, userId);

        Map<String, Object> profile = new LinkedHashMap<>(user.profile());
        profile.putAll(session.profile());

        List<String> diseases = new ArrayList<>(new LinkedHashSet<>(user.diseaseHistory()));
        session.diseaseHistory().stream()
                .filter(disease -> !diseases.contains(disease))
                .forEach(diseases::add);

        Map<String, Object> constraints = new LinkedHashMap<>(user.coreConstraints());
        constraints.putAll(session.coreConstraints());
        return new EffectiveMemory(
                Map.copyOf(profile),
                List.copyOf(diseases),
                Map.copyOf(constraints),
                session,
                user.history());
    }

    public SessionWorkingMemory patchProfile(
            String userId, String sessionId, Map<String, Object> patch) {
        SessionWorkingMemory updated = session(sessionId, userId).withProfilePatch(patch);
        cache.putSession(updated);
        return updated;
    }

    public SessionWorkingMemory updateClinical(
            String userId, String sessionId, ClinicalDecision decision) {
        List<String> diseases = decision.hypotheses().stream()
                .limit(3)
                .map(DiseaseHypothesis::name)
                .toList();
        SessionWorkingMemory updated = session(sessionId, userId)
                .withClinical(decision.symptomCase(), diseases);
        cache.putSession(updated);
        return updated;
    }

    public SessionWorkingMemory patchExplicitFacts(
            String userId, String sessionId, ExplicitMemoryFacts facts) {
        if (facts == null || facts.isEmpty()) {
            return session(sessionId, userId);
        }
        SessionWorkingMemory updated = session(sessionId, userId).withExplicitFacts(facts);
        cache.putSession(updated);
        return updated;
    }

    /** Preserves raw symptom input when the model cannot produce a validated decision. */
    public SymptomCase preserveClinicalInput(String userId, String sessionId, String message) {
        SessionWorkingMemory current = session(sessionId, userId);
        SymptomCase previous = current.symptoms();
        String currentNarrative = message == null ? "" : message.strip();
        String narrative = previous == null
                        || previous.narrative() == null
                        || previous.narrative().isBlank()
                ? currentNarrative
                : previous.narrative() + " | " + currentNarrative;
        SymptomCase preserved = new SymptomCase(
                narrative,
                previous == null ? null : previous.chiefComplaint(),
                previous == null ? null : previous.duration(),
                previous == null ? null : previous.severityScore(),
                previous == null ? List.of() : previous.associatedSymptoms(),
                previous == null ? List.of() : previous.redFlags(),
                previous == null ? List.of() : previous.knownFacts());
        SessionWorkingMemory updated = current.withClinical(preserved, List.of());
        cache.putSession(updated);
        return preserved;
    }

    public SessionWorkingMemory working(String userId, String sessionId) {
        return session(sessionId, userId);
    }

    public List<SessionMemoryRecord> history(String userId) {
        return user(userId).history();
    }

    public boolean deleteHistory(String userId, String sessionId) {
        boolean deleted = persistence.deleteSession(userId, sessionId);
        if (deleted) {
            refreshUser(userId);
            log.info("memory_history_deleted sessionId={}", sessionId);
        }
        return deleted;
    }

    public UserMemorySnapshot refreshUser(String userId) {
        UserMemorySnapshot refreshed = persistence.loadUser(userId);
        cache.putUser(refreshed, userCacheTtl);
        return refreshed;
    }

    public void updateFinalSummary(SessionWorkingMemory memory) {
        cache.putSession(memory);
    }

    public void deleteWorking(String sessionId) {
        cache.deleteSession(sessionId);
    }

    public boolean isFinalized(String userId, String sessionId) {
        return persistence.isFinalized(userId, sessionId);
    }

    private UserMemorySnapshot user(String userId) {
        return cache.getUser(userId).orElseGet(() -> {
            UserMemorySnapshot loaded = persistence.loadUser(userId);
            cache.putUser(loaded, userCacheTtl);
            log.info(
                    "memory_user_cache_rehydrated hasLongTermProfile={} historyCount={}",
                    loaded.hasLongTermProfile(),
                    loaded.history().size());
            return loaded;
        });
    }

    private SessionWorkingMemory session(String sessionId, String expectedUserId) {
        SessionWorkingMemory memory = cache.getSession(sessionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Session working memory is unavailable: " + sessionId));
        if (!expectedUserId.equals(memory.userId())) {
            throw new IllegalStateException("Session working memory ownership mismatch");
        }
        return memory;
    }
}
