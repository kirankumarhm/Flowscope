package com.example.billing;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
@RequestMapping("/bills")
public class BillingController {
    private final BillingService billingService;
    public BillingController(BillingService billingService) { this.billingService = billingService; }
    @GetMapping
    public List<Object> list() { return billingService.list(); }
}
