package dev.javaharness.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot entry point; scans all {@code dev.javaharness} packages. */
@SpringBootApplication(scanBasePackages = "dev.javaharness")
public class HarnessApplication {

    public static void main(String[] args) {
        SpringApplication.run(HarnessApplication.class, args);
    }
}
