package dev.javaharness.spi;

import java.util.List;

/** A named, reusable collection of local directories. */
public record Workspace(String id, String name, List<String> directories, String icon, String color, long revision) {
    public Workspace { directories = List.copyOf(directories); }
}
