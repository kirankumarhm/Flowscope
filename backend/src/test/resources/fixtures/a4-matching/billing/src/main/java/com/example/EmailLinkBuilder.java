package com.example;

import org.springframework.beans.factory.annotation.Value;

public class EmailLinkBuilder {
    @Value("${app.base-url}")
    private String appUrl;

    public String orderLink(String id) { return String.format("%s/orders/%s", appUrl, id); }
}
