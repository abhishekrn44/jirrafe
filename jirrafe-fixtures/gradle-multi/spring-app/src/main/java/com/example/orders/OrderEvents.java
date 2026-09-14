package com.example.orders;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEvents {
    private final OrderService service;

    public OrderEvents(OrderService service) {
        this.service = service;
    }

    @KafkaListener(topics = "${orders.topic}")
    public void onPlaced(String id) {
        service.notifySms(new Order());
    }
}
