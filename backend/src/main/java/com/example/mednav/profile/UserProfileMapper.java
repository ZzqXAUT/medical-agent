package com.example.mednav.profile;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserProfileMapper {

    @Select("""
            SELECT user_id,
                   CAST(profile AS VARCHAR) AS profile_json,
                   CAST(disease_history AS VARCHAR) AS disease_history_json,
                   CAST(core_constraints AS VARCHAR) AS core_constraints_json,
                   updated_at
            FROM user_profile
            WHERE user_id = #{userId}
            """)
    UserProfileRow findByUserId(@Param("userId") String userId);

    @Insert("""
            INSERT INTO user_profile (
                user_id, profile, disease_history, core_constraints, updated_at
            ) VALUES (
                #{userId}, CAST(#{profileJson} AS JSONB), CAST('[]' AS JSONB),
                CAST('{}' AS JSONB), #{updatedAt}
            )
            """)
    int insert(
            @Param("userId") String userId,
            @Param("profileJson") String profileJson,
            @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE user_profile
            SET profile = CAST(#{profileJson} AS JSONB), updated_at = #{updatedAt}
            WHERE user_id = #{userId}
            """)
    int updateProfile(
            @Param("userId") String userId,
            @Param("profileJson") String profileJson,
            @Param("updatedAt") Instant updatedAt);

    @Insert("""
            INSERT INTO user_profile (
                user_id, profile, disease_history, core_constraints, updated_at
            ) VALUES (
                #{userId}, CAST(#{profileJson} AS JSONB),
                CAST(#{diseaseHistoryJson} AS JSONB),
                CAST(#{coreConstraintsJson} AS JSONB), #{updatedAt}
            )
            """)
    int insertMemory(
            @Param("userId") String userId,
            @Param("profileJson") String profileJson,
            @Param("diseaseHistoryJson") String diseaseHistoryJson,
            @Param("coreConstraintsJson") String coreConstraintsJson,
            @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE user_profile
            SET profile = CAST(#{profileJson} AS JSONB),
                disease_history = CAST(#{diseaseHistoryJson} AS JSONB),
                core_constraints = CAST(#{coreConstraintsJson} AS JSONB),
                updated_at = #{updatedAt}
            WHERE user_id = #{userId}
            """)
    int updateMemory(
            @Param("userId") String userId,
            @Param("profileJson") String profileJson,
            @Param("diseaseHistoryJson") String diseaseHistoryJson,
            @Param("coreConstraintsJson") String coreConstraintsJson,
            @Param("updatedAt") Instant updatedAt);
}
