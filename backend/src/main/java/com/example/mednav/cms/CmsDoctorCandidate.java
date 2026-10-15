package com.example.mednav.cms;

import java.util.List;

public record CmsDoctorCandidate(
        String snapshotId,
        String facilityId,
        String npi,
        String firstName,
        String middleName,
        String lastName,
        String suffix,
        String credential,
        String medicalSchool,
        Integer graduationYear,
        String facilityType,
        List<CmsSpecialtyFact> matchedSpecialties) {

    public CmsDoctorCandidate {
        matchedSpecialties = List.copyOf(matchedSpecialties);
    }
}
