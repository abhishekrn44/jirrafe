package com.example.orders;

import org.springframework.stereotype.Component;

@Component("sms")
public class SmsNotifier implements Notifier {
    @Override
    public void notify(Order order) {
    }
}
