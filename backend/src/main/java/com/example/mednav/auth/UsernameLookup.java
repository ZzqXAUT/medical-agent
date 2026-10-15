package com.example.mednav.auth;

public record UsernameLookup(String username, String canonicalUsername, boolean exists) {}
