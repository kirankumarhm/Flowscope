package com.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;

public class Acknowledger {
    @Value("${kafka.topics.success}")
    private String successTopic;
    private KafkaTemplate<String, String> kafkaTemplate;

    private void ack(String id, String topic, String body) {
        kafkaTemplate.send(topic, body);
    }

    public void handle(String id) {
        this.ack(id, this.successTopic, "{}");
    }
}
