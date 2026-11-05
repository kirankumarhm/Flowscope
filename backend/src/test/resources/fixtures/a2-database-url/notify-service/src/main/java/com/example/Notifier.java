package com.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;

public class Notifier {
    @Value("${notifications.database-url}")
    private String databaseUrl;
    @Value("${notifications.base-url}")
    private String baseUrl;
    private final RestTemplate rest = new RestTemplate();

    public void send(String msg) {
        rest.postForObject(baseUrl + "/push", msg, String.class);
    }
}
