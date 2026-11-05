package com.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;

public class TokenClient {
    @Value("${gql.auth-endpoint}")
    private String authEndpoint;
    private final RestTemplate rest = new RestTemplate();

    public String token() { return rest.postForObject(authEndpoint, "{}", String.class); }
}
