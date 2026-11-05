package com.example;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(ControllerConstants.DEVICES_API)
public class DevicesController {
    @GetMapping
    public String list() { return "[]"; }
}
