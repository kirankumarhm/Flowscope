package com.example;

import org.springframework.kafka.core.KafkaTemplate;

public class EventPublisher {
    private KafkaTemplate<String, String> kafkaTemplate;

    public void publish(String body) { kafkaTemplate.send("shared-events", body); }
}
