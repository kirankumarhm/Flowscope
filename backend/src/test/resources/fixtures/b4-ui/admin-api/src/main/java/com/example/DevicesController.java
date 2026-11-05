package com.example;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/3.0.0/devices")
public class DevicesController {
    @GetMapping
    public String list() { return "[]"; }
}
