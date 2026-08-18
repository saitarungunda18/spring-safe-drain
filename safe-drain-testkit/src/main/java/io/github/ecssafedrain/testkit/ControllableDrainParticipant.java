package io.github.ecssafedrain.testkit;

import io.github.ecssafedrain.core.*;
import java.time.Instant;
import java.util.concurrent.atomic.*;

/** Deterministic, thread-safe participant for framework consumer integration tests. */
public final class ControllableDrainParticipant implements DrainParticipant {
  private final String name;
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private final AtomicLong inFlight = new AtomicLong();

  /** Creates a participant with open admission and zero in-flight work. */
  public ControllableDrainParticipant(String name) {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
    this.name = name;
  }

  /** Sets the simulated in-flight count reported by {@link #status()}. */
  public void setInFlight(long value) {
    if (value < 0) throw new IllegalArgumentException("in-flight cannot be negative");
    inFlight.set(value);
  }

  /** Returns whether the simulated participant currently accepts new work. */
  public boolean accepting() {
    return accepting.get();
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public void stopAcceptingNewWork() {
    accepting.set(false);
  }

  @Override
  public ParticipantDrainStatus status() {
    // A zero count is the test participant's entire drain-safety condition.
    long count = inFlight.get();
    return new ParticipantDrainStatus(name, count == 0, count, "in-flight=" + count, Instant.now());
  }

  @Override
  public void resume() {
    accepting.set(true);
  }
}
