package com.example.mednav;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan({
    "com.example.mednav.mapper",
    "com.example.mednav.auth",
    "com.example.mednav.profile",
    "com.example.mednav.cms",
    "com.example.mednav.conversation"
})
@SpringBootApplication
@EnableScheduling
public class MedNavApplication {

    public static void main(String[] args) {
        SpringApplication.run(MedNavApplication.class, args);
    }
}
