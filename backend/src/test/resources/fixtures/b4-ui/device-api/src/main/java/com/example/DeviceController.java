package com.example;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/device")
public class DeviceController {
    @GetMapping("/{id}")
    public String get(@PathVariable String id) { return id; }
}
