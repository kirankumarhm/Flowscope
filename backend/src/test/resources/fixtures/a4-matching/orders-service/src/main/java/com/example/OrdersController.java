package com.example;

import org.springframework.web.bind.annotation.*;

@RestController
public class OrdersController {
    @GetMapping("/orders/{id}")
    public String get(@PathVariable String id) { return id; }
}
