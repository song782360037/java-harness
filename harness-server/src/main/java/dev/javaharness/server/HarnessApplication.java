package dev.javaharness.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot 启动入口，扫描 {@code dev.javaharness} 下的所有包。 */
@SpringBootApplication(scanBasePackages = "dev.javaharness")
public class HarnessApplication {

    public static void main(String[] args) {
        SpringApplication.run(HarnessApplication.class, args);
    }
}
