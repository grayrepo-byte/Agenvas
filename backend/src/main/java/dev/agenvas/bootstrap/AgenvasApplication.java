package dev.agenvas.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "dev.agenvas")
public class AgenvasApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgenvasApplication.class, args);
    }
}
