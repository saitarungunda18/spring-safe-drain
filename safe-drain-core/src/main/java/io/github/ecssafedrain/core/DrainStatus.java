package io.github.ecssafedrain.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Immutable external view of coordinator, registry, and participant state. */
public record DrainStatus(
    DrainState state,
    Instant startedAt,
    Instant deadline,
    boolean acceptingNewWork,
    Map<String, Long> inFlight,
    List<ParticipantDrainStatus> participants,
    boolean safeToTerminate,
    String lastError) {
  public DrainStatus {
    // Status objects may cross thread and HTTP boundaries, so collections must be immutable.
    inFlight = Map.copyOf(inFlight);
    participants = List.copyOf(participants);
  }
}
