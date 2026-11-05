package com.example.billing;
import java.util.List;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
@Service
public class BillingService {
    private final InvoiceRepository invoiceRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    public BillingService(InvoiceRepository invoiceRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.invoiceRepository = invoiceRepository;
        this.kafkaTemplate = kafkaTemplate;
    }
    public void bill(String orderId) {
        invoiceRepository.save(orderId);
        kafkaTemplate.send("billing-done", orderId);
    }
    public List<Object> list() { return invoiceRepository.findAll(); }
}
