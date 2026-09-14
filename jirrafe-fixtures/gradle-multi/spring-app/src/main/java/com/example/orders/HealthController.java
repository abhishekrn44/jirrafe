package com.example.orders;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/")
public class HealthController implements HealthApi {
    @Override
    public String health() {
        return "ok";
    }
}
