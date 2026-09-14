package com.example.orders;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public class EmailNotifier implements Notifier {
    @Override
    public void notify(Order order) {
    }
}
