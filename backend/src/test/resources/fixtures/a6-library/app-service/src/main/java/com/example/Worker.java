package com.example;

import org.springframework.kafka.core.KafkaTemplate;

public class Worker {
    private KafkaTemplate<String, String> kafkaTemplate;

    public void run() { kafkaTemplate.send("work-done", "x"); }
}
