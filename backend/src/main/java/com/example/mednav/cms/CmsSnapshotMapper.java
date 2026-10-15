package com.example.mednav.cms;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CmsSnapshotMapper {

    @Insert("""
            INSERT INTO cms_provider_snapshot (snapshot_id, status, scheduled_at, created_at)
            VALUES (#{snapshotId}, #{status}, #{scheduledAt}, #{scheduledAt})
            """)
    int insertSnapshot(
            @Param("snapshotId") String snapshotId,
            @Param("status") String status,
            @Param("scheduledAt") Instant scheduledAt);

    @Insert("""
            INSERT INTO cms_source_file (
                snapshot_id, dataset_id, logical_name, source_modified_at, source_released_at,
                resolved_download_url, etag, sha256, row_count, raw_object_key
            ) VALUES (
                #{snapshotId}, #{datasetId}, #{logicalName}, #{sourceModifiedAt}, #{sourceReleasedAt},
                #{resolvedDownloadUrl}, #{etag}, #{sha256}, #{rowCount}, #{rawObjectKey}
            )
            """)
    int insertSourceFile(CmsSourceFileRecord source);

    @Update("""
            UPDATE cms_provider_snapshot SET status = 'VALIDATING'
            WHERE snapshot_id = #{snapshotId}
            """)
    int markValidating(@Param("snapshotId") String snapshotId);

    @Update("""
            UPDATE cms_provider_snapshot
            SET status = 'FAILED', failure_code = #{code}, failure_detail = #{detail}
            WHERE snapshot_id = #{snapshotId}
            """)
    int markFailed(
            @Param("snapshotId") String snapshotId,
            @Param("code") String code,
            @Param("detail") String detail);

    @Update("""
            UPDATE cms_provider_snapshot SET status = 'NO_CHANGE'
            WHERE snapshot_id = #{snapshotId}
            """)
    int markNoChange(@Param("snapshotId") String snapshotId);

    @Select("""
            SELECT s.snapshot_id, s.status, s.scheduled_at, s.published_at,
                   s.failure_code, s.failure_detail
            FROM cms_active_snapshot a
            JOIN cms_provider_snapshot s ON s.snapshot_id = a.snapshot_id
            WHERE a.singleton_id = 1
            """)
    CmsSnapshotSummary findActiveSnapshot();

    @Select("""
            SELECT f.snapshot_id, f.dataset_id, f.logical_name, f.source_modified_at,
                   f.source_released_at, f.resolved_download_url, f.etag, f.sha256,
                   f.row_count, f.raw_object_key
            FROM cms_active_snapshot a
            JOIN cms_source_file f ON f.snapshot_id = a.snapshot_id
            WHERE a.singleton_id = 1
            """)
    List<CmsSourceFileRecord> findActiveSourceFiles();

    @Update("""
            UPDATE cms_refresh_lock
            SET lock_owner = #{owner}, locked_until = #{lockedUntil}
            WHERE singleton_id = 1
              AND (locked_until IS NULL OR locked_until < #{now} OR lock_owner = #{owner})
            """)
    int tryAcquireLock(
            @Param("owner") String owner,
            @Param("now") Instant now,
            @Param("lockedUntil") Instant lockedUntil);

    @Update("""
            UPDATE cms_refresh_lock SET lock_owner = NULL, locked_until = NULL
            WHERE singleton_id = 1 AND lock_owner = #{owner}
            """)
    int releaseLock(@Param("owner") String owner);
}
