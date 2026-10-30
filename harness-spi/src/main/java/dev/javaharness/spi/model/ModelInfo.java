package dev.javaharness.spi.model;

/** Public configuration summary; never contains credentials or endpoint details. */
public record ModelInfo(String provider, String model, boolean configured) {}
