package com.example.lib;

/** Factory consumers are expected to call; hides the implementation type. */
public final class Greeters {
    private Greeters() {
    }

    public static Greeter standard() {
        return new DefaultGreeter("Hello");
    }
}
