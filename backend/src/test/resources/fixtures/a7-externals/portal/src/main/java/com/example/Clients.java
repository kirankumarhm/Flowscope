package com.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;

public class Clients {
    @Value("${billing.base-url}") private String billing;
    @Value("${identity.base-url}") private String identity;
    @Value("${smartsheet.base-url}") private String smartsheet;
    private final RestTemplate rest = new RestTemplate();

    public void call() {
        rest.getForObject(billing + "/x", String.class);
        rest.getForObject(identity + "/y", String.class);
        rest.getForObject(smartsheet + "/z", String.class);
    }
}
