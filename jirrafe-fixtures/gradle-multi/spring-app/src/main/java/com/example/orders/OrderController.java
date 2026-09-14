package com.example.orders;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {
    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @GetMapping
    public List<Order> open() {
        return service.open();
    }

    @GetMapping("/{id}/quote")
    public long quote(@PathVariable long id) {
        Order o = new Order();
        return service.quote(o);
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public Order place(@RequestBody Order order) {
        return service.place(order);
    }
}
