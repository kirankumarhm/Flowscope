package com.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;

public class SoapConfiguration {
    @Value("${inventorysoap.default-url}")
    private String defaultUrl;

    public Jaxb2Marshaller marshaller() {
        Jaxb2Marshaller m = new Jaxb2Marshaller();
        m.setContextPath("com.example.soap");
        return m;
    }
}
