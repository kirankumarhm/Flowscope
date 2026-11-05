package com.example;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {
    @PostMapping("/nonce")
    public String nonce() { return "n"; }
}
