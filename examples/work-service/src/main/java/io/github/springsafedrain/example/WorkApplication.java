package io.github.springsafedrain.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Runnable generic application that demonstrates how a service consumes the Safe Drain starter. */
@SpringBootApplication
public class WorkApplication {
  /** Starts Spring Boot, embedded Tomcat, Actuator, and Safe Drain auto-configuration. */
  public static void main(String[] args) {
    SpringApplication.run(WorkApplication.class, args);
  }
}
