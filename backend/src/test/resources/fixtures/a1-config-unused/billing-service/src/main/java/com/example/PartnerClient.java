package com.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;

public class PartnerClient {
    @Value("${partner.api.base-url}")
    private String baseUrl;
    private final RestTemplate rest = new RestTemplate();

    public String invoice(String id) {
        return rest.getForObject(baseUrl + "/invoices/" + id, String.class);
    }
}
