package com.example.app;

import com.example.lib.Greeters;
import com.example.svc.Clock;

public class Main {
    public static void main(String[] args) {
        System.out.println(Clock.stamp(Greeters.standard().greet(args.length > 0 ? args[0] : "world")));
    }
}
