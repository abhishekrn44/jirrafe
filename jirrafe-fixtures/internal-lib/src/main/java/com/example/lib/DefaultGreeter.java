package com.example.lib;

/** The only implementation shipped in the jar. */
public class DefaultGreeter implements Greeter {
    private final String prefix;

    public DefaultGreeter(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public String greet(String name) {
        return prefix + ", " + name + "!";
    }
}
