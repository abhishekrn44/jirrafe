package com.example.orders;

import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "shipments")
public class Shipment {
    private String id;
    private Long orderId;

    public String getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }
}
