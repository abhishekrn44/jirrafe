package com.example.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class PricingClient {
    private final RestTemplate rest;
    private final String base;

    public PricingClient(RestTemplate rest, @Value("${orders.pricing-url}") String base) {
        this.rest = rest;
        this.base = base;
    }

    public long price(long orderId) {
        Long p = rest.getForObject("http://pricing.internal/api/price/{id}", Long.class, orderId);
        return p == null ? 0 : p;
    }
}
