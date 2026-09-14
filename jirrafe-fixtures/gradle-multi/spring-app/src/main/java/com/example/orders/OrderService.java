package com.example.orders;

import com.example.lib.Greeter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** The service every flow goes through. */
@Service
public class OrderService {
    private final OrderRepository repository;
    private final Notifier notifier;
    private final Notifier sms;
    private final KafkaTemplate<String, String> kafka;
    private final PricingClient pricing;

    @Value("${orders.max}")
    private int max;

    @Value("${orders.undefined-key}")
    private String undefined;

    @Autowired
    private Greeter greeter;

    public OrderService(OrderRepository repository, Notifier notifier, @Qualifier("sms") Notifier sms,
                        KafkaTemplate<String, String> kafka, PricingClient pricing) {
        this.repository = repository;
        this.notifier = notifier;
        this.sms = sms;
        this.kafka = kafka;
        this.pricing = pricing;
    }

    @Transactional
    public Order place(Order order) {
        order.setStatus("OPEN");
        Order saved = repository.save(order);
        notifier.notify(saved);
        greeter.greet(saved.getStatus());
        kafka.send("orders-placed", String.valueOf(saved.getId()));
        return saved;
    }

    /** Calls a @Transactional method on this: the proxy is bypassed. */
    public void placeAll(List<Order> orders) {
        for (Order o : orders) {
            place(o);
        }
    }

    @Cacheable("orders")
    public List<Order> open() {
        return repository.findByStatus("OPEN");
    }

    @Async
    public void notifySms(Order order) {
        sms.notify(order);
    }

    @Scheduled(cron = "0 0 * * * *")
    public void expire() {
        repository.openSince(0).forEach(o -> o.setStatus("EXPIRED"));
    }

    public long quote(Order order) {
        return pricing.price(order.getId());
    }
}
