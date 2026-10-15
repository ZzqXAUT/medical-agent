package com.example.mednav.cms;

public record CmsDoctorMatchRow(
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
        String specialtyType,
        String specialtyName,
        String normalizedSpecialty,
        String facilityType) {}
