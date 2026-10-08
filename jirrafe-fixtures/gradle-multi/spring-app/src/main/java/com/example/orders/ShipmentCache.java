package com.example.orders;

import org.springframework.data.redis.core.RedisHash;

@RedisHash("ShipmentCache")
public class ShipmentCache {
    private String id;

    public String getId() {
        return id;
    }
}
