package dev.javaharness.spi;

/** Public configuration summary; never contains credentials or endpoint details. */
public record ModelInfo(String provider, String model, boolean configured) {}
