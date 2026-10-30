package dev.javaharness.adapters.settings;

/** Internal profile. Credentials remain inside settings, which redacts its string representation. */
public record ModelProfile(String id, String displayName, String reasoningEffort, String providerId, String providerName, HarnessSettings settings) {}
