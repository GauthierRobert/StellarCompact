package com.stellarcompact.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point that wires the whole backend together (api,
 * orchestrator, persistence, agent-runtime). The framework-free engine and
 * galaxy libraries are pulled in transitively and never boot Spring themselves.
 *
 * <p>Component scanning is anchored at {@code com.stellarcompact} so beans from
 * every Spring module are discovered without per-module configuration here.
 */
@SpringBootApplication(scanBasePackages = "com.stellarcompact")
public class StellarCompactApplication {

    public static void main(String[] args) {
        SpringApplication.run(StellarCompactApplication.class, args);
    }
}
