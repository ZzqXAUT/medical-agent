package com.example.mednav.memory;

import com.example.mednav.clinical.SymptomCase;
import com.example.mednav.mapper.SessionMemoryMapper;
import com.example.mednav.profile.UserProfileMapper;
import com.example.mednav.profile.UserProfileRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryPersistenceService {

    private final UserProfileMapper profileMapper;
    private final SessionMemoryMapper sessionMapper;
    private final MemoryJsonCodec json;

    public MemoryPersistenceService(
            UserProfileMapper profileMapper,
            SessionMemoryMapper sessionMapper,
            MemoryJsonCodec json) {
        this.profileMapper = profileMapper;
        this.sessionMapper = sessionMapper;
        this.json = json;
    }

    public UserMemorySnapshot loadUser(String userId) {
        UserProfileRow profileRow = profileMapper.findByUserId(userId);
        List<SessionMemoryRecord> history = sessionMapper.findByUserId(userId).stream()
                .map(this::toRecord)
                .toList();
        if (profileRow == null) {
            return new UserMemorySnapshot(
                    userId, false, Map.of(), List.of(), Map.of(), history);
        }
        return new UserMemorySnapshot(
                userId,
                true,
                json.readMap(profileRow.profileJson()),
                json.readStringList(profileRow.diseaseHistoryJson()),
                json.readMap(profileRow.coreConstraintsJson()),
                history);
    }

    @Transactional
    public MemoryCommitResult commit(SessionWorkingMemory memory, Instant createdAt) {
        UserProfileRow existingProfile = profileMapper.findByUserId(memory.userId());
        Map<String, Object> mergedProfile = new LinkedHashMap<>();
        List<String> mergedDiseaseHistory = new ArrayList<>();
        Map<String, Object> mergedConstraints = new LinkedHashMap<>();
        if (existingProfile != null) {
            mergedProfile.putAll(json.readMap(existingProfile.profileJson()));
            mergedDiseaseHistory.addAll(json.readStringList(existingProfile.diseaseHistoryJson()));
            mergedConstraints.putAll(json.readMap(existingProfile.coreConstraintsJson()));
        }
        mergedProfile.putAll(memory.profile());
        mergedDiseaseHistory = new ArrayList<>(new LinkedHashSet<>(mergedDiseaseHistory));
        for (String disease : memory.diseaseHistory()) {
            if (!mergedDiseaseHistory.contains(disease)) {
                mergedDiseaseHistory.add(disease);
            }
        }
        mergedConstraints.putAll(memory.coreConstraints());

        boolean hasProfileUpdates = !memory.profile().isEmpty()
                || !memory.diseaseHistory().isEmpty()
                || !memory.coreConstraints().isEmpty();
        if (hasProfileUpdates) {
            Instant now = Instant.now();
            if (existingProfile == null) {
                profileMapper.insertMemory(
                        memory.userId(),
                        json.write(mergedProfile),
                        json.write(mergedDiseaseHistory),
                        json.write(mergedConstraints),
                        now);
            } else {
                profileMapper.updateMemory(
                        memory.userId(),
                        json.write(mergedProfile),
                        json.write(mergedDiseaseHistory),
                        json.write(mergedConstraints),
                        now);
            }
        }

        String symptomsJson = json.write(memory.symptoms() == null ? List.of() : memory.symptoms());
        String diseasesJson = json.write(memory.preliminaryDiseases());
        SessionMemoryRow existingSession =
                sessionMapper.findOne(memory.sessionId(), memory.userId());
        if (existingSession == null) {
            sessionMapper.insertClinical(
                    memory.sessionId(),
                    memory.userId(),
                    symptomsJson,
                    diseasesJson,
                    createdAt);
            sessionMapper.insertSummary(
                    memory.sessionId(),
                    memory.userId(),
                    memory.summary(),
                    createdAt);
        } else {
            sessionMapper.updateClinical(
                    memory.sessionId(), memory.userId(), symptomsJson, diseasesJson);
            if (existingSession.summary() == null) {
                sessionMapper.insertSummary(
                        memory.sessionId(),
                        memory.userId(),
                        memory.summary(),
                        createdAt);
            } else {
                sessionMapper.updateSummary(
                        memory.sessionId(), memory.userId(), memory.summary());
            }
        }
        return new MemoryCommitResult(hasProfileUpdates, true, true);
    }

    @Transactional
    public boolean deleteSession(String userId, String sessionId) {
        SessionMemoryRow existing = sessionMapper.findOne(sessionId, userId);
        if (existing == null) {
            return false;
        }
        sessionMapper.deleteSummary(sessionId, userId);
        return sessionMapper.deleteClinical(sessionId, userId) == 1;
    }

    public boolean isFinalized(String userId, String sessionId) {
        return sessionMapper.findOne(sessionId, userId) != null;
    }

    private SessionMemoryRecord toRecord(SessionMemoryRow row) {
        return new SessionMemoryRecord(
                row.sessionId(),
                json.read(row.symptomsJson(), SymptomCase.class),
                json.readStringList(row.preliminaryDiseasesJson()),
                row.summary(),
                row.createdAt());
    }
}
