package com.example.app;

import com.example.kt.Formatter;
import com.example.lib.Greeter;
import com.example.lib.Greeters;

public class Main {
    public static void main(String[] args) {
        System.out.println(banner(args.length > 0 ? args[0] : "world"));
    }

    /** Crosses two boundaries: the internal jar and the Kotlin module. */
    static String banner(String name) {
        Greeter greeter = Greeters.standard();
        return Formatter.INSTANCE.shout(greeter.greet(name));
    }
}
