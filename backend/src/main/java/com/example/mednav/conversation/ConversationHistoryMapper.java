package com.example.mednav.conversation;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ConversationHistoryMapper {

    @Insert("""
            INSERT INTO conversation_session (
                session_id, conversation_id, user_id, title, status,
                created_at, last_activity_at
            ) VALUES (
                #{sessionId}, #{conversationId}, #{userId}, #{title}, 'ACTIVE',
                #{createdAt}, #{createdAt}
            )
            """)
    int insertSession(
            @Param("sessionId") String sessionId,
            @Param("conversationId") String conversationId,
            @Param("userId") String userId,
            @Param("title") String title,
            @Param("createdAt") Instant createdAt);

    @Insert("""
            INSERT INTO conversation_message (
                id, session_id, run_id, sequence_no, role, content,
                message_type, structured_payload, created_at
            ) VALUES (
                #{id}, #{sessionId}, #{runId}, #{sequenceNo},
                #{role}, #{content}, #{messageType}, #{structuredPayload}, #{createdAt}
            )
            """)
    int insertMessage(ConversationMessageRow message);

    @Select("""
            SELECT session_id, conversation_id, user_id, title, status,
                   created_at, last_activity_at, completed_at, completion_reason
            FROM conversation_session
            WHERE user_id = #{userId}
            ORDER BY last_activity_at DESC, created_at DESC
            """)
    List<ConversationSessionRow> findSessionsByUserId(@Param("userId") String userId);

    @Select("""
            SELECT session_id, conversation_id, user_id, title, status,
                   created_at, last_activity_at, completed_at, completion_reason
            FROM conversation_session
            WHERE session_id = #{sessionId} AND user_id = #{userId}
            """)
    ConversationSessionRow findOwnedSession(
            @Param("sessionId") String sessionId,
            @Param("userId") String userId);

    @Select("""
            SELECT id, session_id, run_id, sequence_no, role, content,
                   message_type, structured_payload, created_at
            FROM conversation_message
            WHERE session_id = #{sessionId}
            ORDER BY sequence_no ASC
            """)
    List<ConversationMessageRow> findMessages(@Param("sessionId") String sessionId);

    @Update("""
            UPDATE conversation_session
            SET last_activity_at = #{lastActivityAt}
            WHERE session_id = #{sessionId} AND status = 'ACTIVE'
            """)
    int touch(
            @Param("sessionId") String sessionId,
            @Param("lastActivityAt") Instant lastActivityAt);

    @Update("""
            UPDATE conversation_session
            SET title = #{title}
            WHERE session_id = #{sessionId} AND title = '新会话'
            """)
    int setInitialTitle(
            @Param("sessionId") String sessionId,
            @Param("title") String title);

    @Update("""
            UPDATE conversation_session
            SET status = 'COMPLETED', completed_at = #{completedAt},
                completion_reason = #{reason}, last_activity_at = #{completedAt}
            WHERE session_id = #{sessionId}
            """)
    int complete(
            @Param("sessionId") String sessionId,
            @Param("completedAt") Instant completedAt,
            @Param("reason") String reason);
}
