package com.example.mednav.cms;

public record CmsHospital(
        String snapshotId,
        String facilityId,
        String facilityName,
        String address,
        String city,
        String state,
        String zipCode,
        String county,
        String telephone,
        String hospitalType,
        String hospitalOwnership,
        Boolean emergencyServices,
        Boolean birthingFriendly,
        Integer overallRating,
        String overallRatingFootnote) {}
