package dev.agenvas.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Application entry point for the Agenvas modular monolith. */
@SpringBootApplication(scanBasePackages = "dev.agenvas")
@ConfigurationPropertiesScan(basePackages = "dev.agenvas")
public class AgenvasApplication {

    /** Starts the HTTP application and its background infrastructure. */
    public static void main(String[] args) {
        SpringApplication.run(AgenvasApplication.class, args);
    }
}
