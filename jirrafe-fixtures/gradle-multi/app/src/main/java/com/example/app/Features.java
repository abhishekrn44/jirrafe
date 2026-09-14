package com.example.app;

import com.example.lib.Greeter;
import com.example.lib.Greeters;

import java.util.List;
import java.util.stream.Collectors;

import static com.example.lib.Greeters.standard;

/** Modern Java constructs the source tier must handle. */
public class Features {
    public sealed interface Shape permits Circle, Square {
        double area();
    }

    public record Circle(double r) implements Shape {
        @Override
        public double area() {
            return Math.PI * r * r;
        }
    }

    public record Square(double side) implements Shape {
        @Override
        public double area() {
            return side * side;
        }
    }

    public enum Color {
        RED, GREEN;

        Color next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    static final String TEXT = """
        multi
        line""";

    static Greeter fromStaticImport;

    static {
        fromStaticImport = standard();
    }

    /** Pattern matching and a switch expression. */
    static String describe(Shape s) {
        if (s instanceof Circle c) {
            return "circle " + c.r();
        }
        Color color = s.area() > 1 ? Color.RED : Color.GREEN;
        return switch (color) {
            case RED -> "big square";
            case GREEN -> "small square";
        };
    }

    static double total(Shape... shapes) {
        double t = 0;
        for (Shape s : shapes) {
            t += s.area();
        }
        return t;
    }

    static List<String> names(List<? extends Shape> shapes) {
        return shapes.stream().map(Features::describe).map(String::toUpperCase).collect(Collectors.toList());
    }

    static Runnable task(String label) {
        return () -> Greeters.standard().greet(label);
    }

    class Inner {
        String tag() {
            return TEXT;
        }
    }

    static Object local() {
        class Local implements Runnable {
            @Override
            public void run() {
            }
        }
        return new Local();
    }

    static Object anon() {
        return new Object() {
            @Override
            public String toString() {
                return "anon";
            }
        };
    }
}
