package com.example;

import org.springframework.kafka.core.KafkaTemplate;

public class Old {
    private KafkaTemplate<String, String> kafkaTemplate;

    public void run() { kafkaTemplate.send("old-topic", "x"); }
}
