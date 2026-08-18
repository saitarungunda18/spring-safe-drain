package io.github.springsafedrain.core;

import java.time.Instant;

/** Immutable diagnostic and safety result reported by a {@link DrainParticipant}. */
public record ParticipantDrainStatus(
    String name, boolean drained, Long inFlight, String detail, Instant lastUpdated) {
  public ParticipantDrainStatus {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
    // Normalize optional diagnostic fields so API consumers do not handle avoidable nulls.
    detail = detail == null ? "" : detail;
    lastUpdated = lastUpdated == null ? Instant.now() : lastUpdated;
  }
}
