package com.docflow.docflow_backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Keeps the Railway free-tier server awake by self-pinging every 10 minutes.
 * Without this, Railway sleeps the app after ~15 minutes of inactivity,
 * causing cold-start failures for the next user.
 */
@Component
@EnableScheduling
public class KeepAliveScheduler {

    // Railway injects the PORT env var; default to 8080 for local dev
    @Value("${server.port:8080}")
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Scheduled(fixedDelay = 10 * 60 * 1000) // every 10 minutes
    public void keepAlive() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/ping"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            System.out.println("[KeepAlive] Self-ping OK: " + response.statusCode());
        } catch (Exception e) {
            System.err.println("[KeepAlive] Self-ping failed: " + e.getMessage());
        }
    }
}
