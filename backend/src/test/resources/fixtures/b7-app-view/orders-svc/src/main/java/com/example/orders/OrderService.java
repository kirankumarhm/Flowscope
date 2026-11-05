package com.example.orders;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
@Service
public class OrderService {
    private final KafkaTemplate<String, String> kafkaTemplate;
    public OrderService(KafkaTemplate<String, String> kafkaTemplate) { this.kafkaTemplate = kafkaTemplate; }
    public void create(String orderId) { kafkaTemplate.send("orders-created", orderId); }
    @KafkaListener(topics = "billing-done")
    public void onBilled(String orderId) { }
}
