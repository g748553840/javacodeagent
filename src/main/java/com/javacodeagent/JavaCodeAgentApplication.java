package com.javacodeagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootApplication
@EnableScheduling
public class JavaCodeAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaCodeAgentApplication.class, args);
    }

    @Bean
    public WebClient webClient() {
        return WebClient.builder()
            .codecs(configurer -> configurer.defaultCodecs()
                .maxInMemorySize(10 * 1024 * 1024))  // 10 MB buffer for large SSE payloads
            .build();
    }
}