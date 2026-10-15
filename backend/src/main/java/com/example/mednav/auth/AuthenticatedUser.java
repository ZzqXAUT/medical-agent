package com.example.mednav.auth;

public record AuthenticatedUser(String userId, String username, boolean newUser) {}
