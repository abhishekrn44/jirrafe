package com.example.orders;

import org.springframework.web.bind.annotation.GetMapping;

/** Mapping declared on the interface, the way OpenAPI generators emit it. */
public interface HealthApi {
    @GetMapping("/health")
    String health();
}
