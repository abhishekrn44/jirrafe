package com.example.lib;

/** Produces a greeting for a name. Lives in an internal jar, so consumers only see bytecode unless the sources jar is fetched. */
public interface Greeter {
    String greet(String name);
}
