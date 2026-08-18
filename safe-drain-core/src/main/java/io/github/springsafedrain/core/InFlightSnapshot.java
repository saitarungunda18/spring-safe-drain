package io.github.springsafedrain.core;

import java.util.Map;

/** Immutable point-in-time view of admission and category counts. */
public record InFlightSnapshot(boolean acceptingNewWork, Map<String, Long> byCategory) {
  public InFlightSnapshot {
    // Prevent callers from mutating registry state through a returned snapshot.
    byCategory = Map.copyOf(byCategory);
  }

  /** Returns the sum of all category counts. */
  public long total() {
    return byCategory.values().stream().mapToLong(Long::longValue).sum();
  }
}
