package com.example;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/api")
public class DashboardController {
    @GetMapping("/summary")
    public String summary() { return "ok"; }
}
