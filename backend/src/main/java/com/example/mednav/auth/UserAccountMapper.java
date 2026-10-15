package com.example.mednav.auth;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserAccountMapper {

    @Select("""
            SELECT id, username, canonical_username, password_hash, account_status, created_at, updated_at
            FROM user_account
            WHERE canonical_username = #{canonicalUsername}
            """)
    UserAccount findByCanonicalUsername(@Param("canonicalUsername") String canonicalUsername);

    @Insert("""
            INSERT INTO user_account (
                id, username, canonical_username, password_hash, account_status, created_at, updated_at
            ) VALUES (
                #{id}, #{username}, #{canonicalUsername}, #{passwordHash},
                #{accountStatus}, #{createdAt}, #{updatedAt}
            )
            """)
    int insert(UserAccount account);
}
