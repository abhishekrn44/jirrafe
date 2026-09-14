package com.example.svc;

/** A sibling module resolved through the reactor, not from a repository. */
public final class Clock {
    private Clock() {
    }

    public static String stamp(String message) {
        return "[" + System.currentTimeMillis() + "] " + message;
    }
}
