package com.example.mednav.cms;

import java.util.List;

public enum CmsDataset {
    FACILITY_AFFILIATION(
            "27ea-46a8",
            "facility_affiliation",
            "Facility_Affiliation.csv",
            List.of(
                    "NPI",
                    "Ind_PAC_ID",
                    "Provider Last Name",
                    "Provider First Name",
                    "Provider Middle Name",
                    "suff",
                    "facility_type",
                    "Facility Affiliations Certification Number",
                    "Facility Type Certification Number")),
    DOCTOR_NATIONAL(
            "mj5m-pzi6",
            "doctor_national",
            "DAC_NationalDownloadableFile.csv",
            List.of(
                    "NPI", "Ind_PAC_ID", "Ind_enrl_ID", "Provider Last Name",
                    "Provider First Name", "Provider Middle Name", "suff", "gndr", "Cred",
                    "Med_sch", "Grd_yr", "pri_spec", "sec_spec_1", "sec_spec_2",
                    "sec_spec_3", "sec_spec_4", "sec_spec_all", "Telehlth", "Facility Name",
                    "org_pac_id", "num_org_mem", "adr_ln_1", "adr_ln_2", "ln_2_sprs",
                    "City/Town", "State", "ZIP Code", "Telephone Number", "ind_assgn",
                    "grp_assgn", "adrs_id")),
    HOSPITAL_GENERAL(
            "xubh-q36u",
            "hospital_general",
            "Hospital_General_Information.csv",
            List.of(
                    "Facility ID", "Facility Name", "Address", "City/Town", "State", "ZIP Code",
                    "County/Parish", "Telephone Number", "Hospital Type", "Hospital Ownership",
                    "Emergency Services", "Meets criteria for birthing friendly designation",
                    "Hospital overall rating", "Hospital overall rating footnote",
                    "MORT Group Measure Count", "Count of Facility MORT Measures",
                    "Count of MORT Measures Better", "Count of MORT Measures No Different",
                    "Count of MORT Measures Worse", "MORT Group Footnote",
                    "Safety Group Measure Count", "Count of Facility Safety Measures",
                    "Count of Safety Measures Better", "Count of Safety Measures No Different",
                    "Count of Safety Measures Worse", "Safety Group Footnote",
                    "READM Group Measure Count", "Count of Facility READM Measures",
                    "Count of READM Measures Better", "Count of READM Measures No Different",
                    "Count of READM Measures Worse", "READM Group Footnote",
                    "Pt Exp Group Measure Count", "Count of Facility Pt Exp Measures",
                    "Pt Exp Group Footnote", "TE Group Measure Count",
                    "Count of Facility TE Measures", "TE Group Footnote"));

    private final String datasetId;
    private final String logicalName;
    private final String defaultFileName;
    private final List<String> expectedHeaders;

    CmsDataset(
            String datasetId,
            String logicalName,
            String defaultFileName,
            List<String> expectedHeaders) {
        this.datasetId = datasetId;
        this.logicalName = logicalName;
        this.defaultFileName = defaultFileName;
        this.expectedHeaders = List.copyOf(expectedHeaders);
    }

    public String datasetId() {
        return datasetId;
    }

    public String logicalName() {
        return logicalName;
    }

    public String defaultFileName() {
        return defaultFileName;
    }

    public List<String> expectedHeaders() {
        return expectedHeaders;
    }
}
