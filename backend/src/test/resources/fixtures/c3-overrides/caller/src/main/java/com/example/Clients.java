package com.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;

public class Clients {
    @Value("${gateway.base-url}") private String gateway;
    @Value("${partner.base-url}") private String partner;
    private final RestTemplate rest = new RestTemplate();

    public void call() {
        rest.getForObject(gateway, String.class);
        rest.getForObject(partner + "/bill", String.class);
    }
}
