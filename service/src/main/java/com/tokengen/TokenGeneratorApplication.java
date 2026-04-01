package com.tokengen;

import com.tokengen.config.EnvFileLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TokenGeneratorApplication {
    public static void main(String[] args) {
        EnvFileLoader.load();
        SpringApplication.run(TokenGeneratorApplication.class, args);
    }
}
