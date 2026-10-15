package com.example.mednav.mapper;

import com.example.mednav.memory.SessionMemoryRow;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SessionMemoryMapper {

    @Select("""
            SELECT c.session_id,
                   c.user_id,
                   CAST(c.symptoms AS VARCHAR) AS symptoms_json,
                   CAST(c.preliminary_diseases AS VARCHAR) AS preliminary_diseases_json,
                   s.summary,
                   c.created_at
            FROM session_clinical c
            LEFT JOIN session_summary s
              ON s.session_id = c.session_id AND s.user_id = c.user_id
            WHERE c.user_id = #{userId}
            ORDER BY c.created_at DESC, c.session_id DESC
            """)
    List<SessionMemoryRow> findByUserId(@Param("userId") String userId);

    @Select("""
            SELECT c.session_id,
                   c.user_id,
                   CAST(c.symptoms AS VARCHAR) AS symptoms_json,
                   CAST(c.preliminary_diseases AS VARCHAR) AS preliminary_diseases_json,
                   s.summary,
                   c.created_at
            FROM session_clinical c
            LEFT JOIN session_summary s
              ON s.session_id = c.session_id AND s.user_id = c.user_id
            WHERE c.session_id = #{sessionId} AND c.user_id = #{userId}
            """)
    SessionMemoryRow findOne(
            @Param("sessionId") String sessionId, @Param("userId") String userId);

    @Insert("""
            INSERT INTO session_clinical (
                session_id, user_id, symptoms, preliminary_diseases, created_at
            ) VALUES (
                #{sessionId}, #{userId}, CAST(#{symptomsJson} AS JSONB),
                CAST(#{preliminaryDiseasesJson} AS JSONB), #{createdAt}
            )
            """)
    int insertClinical(
            @Param("sessionId") String sessionId,
            @Param("userId") String userId,
            @Param("symptomsJson") String symptomsJson,
            @Param("preliminaryDiseasesJson") String preliminaryDiseasesJson,
            @Param("createdAt") Instant createdAt);

    @Update("""
            UPDATE session_clinical
            SET symptoms = CAST(#{symptomsJson} AS JSONB),
                preliminary_diseases = CAST(#{preliminaryDiseasesJson} AS JSONB)
            WHERE session_id = #{sessionId} AND user_id = #{userId}
            """)
    int updateClinical(
            @Param("sessionId") String sessionId,
            @Param("userId") String userId,
            @Param("symptomsJson") String symptomsJson,
            @Param("preliminaryDiseasesJson") String preliminaryDiseasesJson);

    @Insert("""
            INSERT INTO session_summary (session_id, user_id, summary, created_at)
            VALUES (#{sessionId}, #{userId}, #{summary}, #{createdAt})
            """)
    int insertSummary(
            @Param("sessionId") String sessionId,
            @Param("userId") String userId,
            @Param("summary") String summary,
            @Param("createdAt") Instant createdAt);

    @Update("""
            UPDATE session_summary
            SET summary = #{summary}
            WHERE session_id = #{sessionId} AND user_id = #{userId}
            """)
    int updateSummary(
            @Param("sessionId") String sessionId,
            @Param("userId") String userId,
            @Param("summary") String summary);

    @Delete("""
            DELETE FROM session_summary
            WHERE session_id = #{sessionId} AND user_id = #{userId}
            """)
    int deleteSummary(
            @Param("sessionId") String sessionId, @Param("userId") String userId);

    @Delete("""
            DELETE FROM session_clinical
            WHERE session_id = #{sessionId} AND user_id = #{userId}
            """)
    int deleteClinical(
            @Param("sessionId") String sessionId, @Param("userId") String userId);
}

