package com.example.mednav.cms;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CmsProviderQueryMapper {

    @Select("""
            SELECT snapshot_id
            FROM cms_active_snapshot
            WHERE singleton_id = 1
            """)
    String findActiveSnapshotId();

    @Select("""
            SELECT h.snapshot_id, h.facility_id, h.facility_name, h.address, h.city, h.state,
                   h.zip_code, h.county, h.telephone, h.hospital_type, h.hospital_ownership,
                   h.emergency_services, h.birthing_friendly, h.overall_rating,
                   h.overall_rating_footnote
            FROM cms_active_snapshot a
            JOIN cms_hospital h ON h.snapshot_id = a.snapshot_id
            WHERE a.singleton_id = 1
              AND UPPER(h.state) = UPPER(#{state})
              AND UPPER(h.city) = UPPER(#{city})
            ORDER BY h.facility_id
            """)
    List<CmsHospital> findActiveHospitalsByCity(
            @Param("state") String state, @Param("city") String city);

    @Select("""
            SELECT h.snapshot_id, h.facility_id, h.facility_name, h.address, h.city, h.state,
                   h.zip_code, h.county, h.telephone, h.hospital_type, h.hospital_ownership,
                   h.emergency_services, h.birthing_friendly, h.overall_rating,
                   h.overall_rating_footnote
            FROM cms_hospital h
            WHERE h.snapshot_id = #{snapshotId}
              AND UPPER(h.state) = UPPER(#{state})
              AND UPPER(h.city) = UPPER(#{city})
            ORDER BY h.facility_id
            """)
    List<CmsHospital> findHospitalsBySnapshotAndCity(
            @Param("snapshotId") String snapshotId,
            @Param("state") String state,
            @Param("city") String city);

    @Select("""
            <script>
            SELECT h.snapshot_id, h.facility_id, h.facility_name, h.address, h.city, h.state,
                   h.zip_code, h.county, h.telephone, h.hospital_type, h.hospital_ownership,
                   h.emergency_services, h.birthing_friendly, h.overall_rating,
                   h.overall_rating_footnote
            FROM cms_hospital h
            WHERE h.snapshot_id = #{snapshotId}
              AND h.facility_id IN
              <foreach collection="facilityIds" item="facilityId" open="(" separator="," close=")">
                #{facilityId}
              </foreach>
            ORDER BY h.facility_id
            </script>
            """)
    List<CmsHospital> findHospitalsBySnapshotAndIds(
            @Param("snapshotId") String snapshotId,
            @Param("facilityIds") List<String> facilityIds);

    @Select("""
            <script>
            SELECT d.snapshot_id, a.facility_id, d.npi, d.first_name, d.middle_name,
                   d.last_name, d.suffix, d.credential, d.medical_school, d.graduation_year,
                   s.specialty_type, s.specialty_name, s.normalized_specialty, a.facility_type
            FROM cms_active_snapshot active
            JOIN cms_doctor_hospital_affiliation a ON a.snapshot_id = active.snapshot_id
            JOIN cms_doctor d ON d.snapshot_id = a.snapshot_id AND d.npi = a.npi
            JOIN cms_doctor_specialty s ON s.snapshot_id = d.snapshot_id AND s.npi = d.npi
            WHERE active.singleton_id = 1
              AND a.facility_id = #{facilityId}
              AND s.normalized_specialty IN
              <foreach collection="specialties" item="specialty" open="(" separator="," close=")">
                #{specialty}
              </foreach>
            ORDER BY d.npi, s.specialty_type, s.specialty_ordinal
            </script>
            """)
    List<CmsDoctorMatchRow> findActiveDoctorMatches(
            @Param("facilityId") String facilityId,
            @Param("specialties") List<String> normalizedSpecialties);

    @Select("""
            <script>
            SELECT d.snapshot_id, a.facility_id, d.npi, d.first_name, d.middle_name,
                   d.last_name, d.suffix, d.credential, d.medical_school, d.graduation_year,
                   s.specialty_type, s.specialty_name, s.normalized_specialty, a.facility_type
            FROM cms_doctor_hospital_affiliation a
            JOIN cms_doctor d ON d.snapshot_id = a.snapshot_id AND d.npi = a.npi
            JOIN cms_doctor_specialty s ON s.snapshot_id = d.snapshot_id AND s.npi = d.npi
            WHERE a.snapshot_id = #{snapshotId}
              AND a.facility_id = #{facilityId}
              AND s.normalized_specialty IN
              <foreach collection="specialties" item="specialty" open="(" separator="," close=")">
                #{specialty}
              </foreach>
            ORDER BY d.npi, s.specialty_type, s.specialty_ordinal
            </script>
            """)
    List<CmsDoctorMatchRow> findDoctorMatches(
            @Param("snapshotId") String snapshotId,
            @Param("facilityId") String facilityId,
            @Param("specialties") List<String> normalizedSpecialties);

    @Select("""
            SELECT DISTINCT s.normalized_specialty
            FROM cms_active_snapshot a
            JOIN cms_doctor_specialty s ON s.snapshot_id = a.snapshot_id
            WHERE a.singleton_id = 1
            ORDER BY s.normalized_specialty
            """)
    List<String> findActiveSpecialtyVocabulary();
}
