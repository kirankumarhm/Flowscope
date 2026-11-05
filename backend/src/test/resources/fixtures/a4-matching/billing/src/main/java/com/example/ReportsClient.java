package com.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;

public class ReportsClient {
    @Value("${reports.base-url}")
    private String reportsUrl;
    private final RestTemplate rest = new RestTemplate();

    public String summary() { return rest.getForObject(reportsUrl + "/summary", String.class); }
}
