package com.example;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
public class ReportsController {
    @GetMapping("/{id}")
    public String get(@PathVariable String id) { return id; }
}
