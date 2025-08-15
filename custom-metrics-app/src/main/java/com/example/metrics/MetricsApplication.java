package com.example.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class MetricsApplication {

    public static void main(String[] args) {
        SpringApplication.run(MetricsApplication.class, args);
    }

    @Bean
    Counter customCounter(MeterRegistry registry) {
        return Counter.builder("custom_incrementing_metric")
                .description("A custom counter that increments on /tick")
                .register(registry);
    }

    @Bean
    CommandLineRunner init() {
        return args -> {
            // no-op initialization
        };
    }

    @RestController
    static class TickController {
        private final Counter counter;

        TickController(Counter counter) {
            this.counter = counter;
        }

        @GetMapping("/tick")
        public String tick() {
            counter.increment();
            return "ok";
        }
    }
}



