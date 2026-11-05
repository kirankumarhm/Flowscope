package com.example;

import org.springframework.web.bind.annotation.*;

@RestController
public class DashboardController {
    @GetMapping("/admin/reports")
    public String reports() { return "[]"; }
}
