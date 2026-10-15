package com.example.mednav.cms;

import java.util.List;

public record CmsHospitalSearchResult(String snapshotId, List<CmsHospital> hospitals) {
    public CmsHospitalSearchResult {
        hospitals = hospitals == null ? List.of() : List.copyOf(hospitals);
    }
}
