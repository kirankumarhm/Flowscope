package com.example.billing;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
@Component
public class OrderEventListener {
    private final BillingService billingService;
    public OrderEventListener(BillingService billingService) { this.billingService = billingService; }
    @KafkaListener(topics = "orders-created")
    public void onOrder(String orderId) { billingService.bill(orderId); }
}
