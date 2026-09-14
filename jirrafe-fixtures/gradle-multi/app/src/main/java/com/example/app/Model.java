package com.example.app;

import lombok.Data;

/** Lombok generates the accessors; the source tier must still see them. */
@Data
public class Model {
    private String name;
    private int age;
}
