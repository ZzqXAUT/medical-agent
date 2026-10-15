package com.example.mednav.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder(@Value("${mednav.auth.bcrypt-strength:12}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }
}
