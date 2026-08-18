package io.github.springsafedrain.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Coordinates admission, participants, deadlines, and lifecycle state for one process. */
public final class DrainCoordinator implements AutoCloseable {
  // Every state transition is serialized through this monitor.
  private final Object lock = new Object();
  private final InFlightWorkRegistry registry;
  private final List<DrainParticipant> participants;
  private final Duration timeout;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final CopyOnWriteArrayList<Consumer<DrainLifecycleEvent>> listeners =
      new CopyOnWriteArrayList<>();
  private DrainState state = DrainState.ACTIVE;
  private Instant startedAt;
  private Instant deadline;
  private String lastError;
  private ScheduledFuture<?> timeoutTask;

  /** Creates a coordinator with a UTC clock and a dedicated daemon timeout thread. */
  public DrainCoordinator(
      InFlightWorkRegistry registry, List<DrainParticipant> participants, Duration timeout) {
    this(
        registry,
        participants,
        timeout,
        Clock.systemUTC(),
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "safe-drain-coordinator");
              t.setDaemon(true);
              return t;
            }));
  }

  DrainCoordinator(
      InFlightWorkRegistry registry,
      List<DrainParticipant> participants,
      Duration timeout,
      Clock clock,
      ScheduledExecutorService scheduler) {
    this.registry = Objects.requireNonNull(registry);
    this.participants = List.copyOf(participants);
    if (timeout == null || timeout.isNegative() || timeout.isZero())
      throw new IllegalArgumentException("timeout must be positive");
    this.timeout = timeout;
    this.clock = clock;
    this.scheduler = scheduler;
    // Completion is event-driven: no polling loop is needed while requests finish.
    registry.onZero(this::evaluateCompletion);
  }

  /** Starts an idempotent drain and returns its latest status. */
  public DrainStatus startDrain() {
    DrainLifecycleEvent startEvent;
    DrainLifecycleEvent completionEvent = null;
    synchronized (lock) {
      if (state == DrainState.DRAINING || state == DrainState.DRAINED) return statusLocked();
      if (state != DrainState.ACTIVE)
        throw new IllegalStateException("cannot start drain from " + state);
      DrainState previous = state;
      state = DrainState.DRAINING;
      startedAt = clock.instant();
      deadline = startedAt.plus(timeout);
      lastError = null;
      registry.closeAdmission();
      for (DrainParticipant participant : participants) {
        try {
          participant.stopAcceptingNewWork();
        } catch (RuntimeException ex) {
          lastError = "Participant " + participant.name() + " failed: " + ex.getMessage();
        }
      }
      // Only one deadline task exists for a drain operation.
      timeoutTask = scheduler.schedule(this::timeout, timeout.toNanos(), TimeUnit.NANOSECONDS);
      startEvent = new DrainLifecycleEvent(previous, state, startedAt, "drain started");
      completionEvent = evaluateCompletionLocked();
    }
    publish(startEvent);
    if (completionEvent != null) publish(completionEvent);
    return status();
  }

  /** Resumes admission; calling this while already active is a successful no-op. */
  public DrainStatus abort() {
    DrainLifecycleEvent event;
    synchronized (lock) {
      if (state == DrainState.ACTIVE) return statusLocked();
      if (state != DrainState.DRAINING
          && state != DrainState.DRAINED
          && state != DrainState.TIMED_OUT)
        throw new IllegalStateException("cannot abort from " + state);
      if (timeoutTask != null) timeoutTask.cancel(false);
      for (DrainParticipant participant : participants) participant.resume();
      registry.openAdmission();
      DrainState previous = state;
      state = DrainState.ACTIVE;
      startedAt = null;
      deadline = null;
      lastError = null;
      event = new DrainLifecycleEvent(previous, state, clock.instant(), "drain aborted");
    }
    publish(event);
    return status();
  }

  /** Returns an immutable snapshot of the latest process safety state. */
  public DrainStatus status() {
    synchronized (lock) {
      return statusLocked();
    }
  }

  /** Allows an asynchronous participant to notify the coordinator after its status changes. */
  public void requestEvaluation() {
    evaluateCompletion();
  }

  /** Registers a listener for successfully published lifecycle transitions. */
  public void onLifecycleEvent(Consumer<DrainLifecycleEvent> listener) {
    listeners.add(Objects.requireNonNull(listener));
  }

  private void evaluateCompletion() {
    DrainLifecycleEvent event = null;
    synchronized (lock) {
      event = evaluateCompletionLocked();
    }
    if (event != null) publish(event);
  }

  private DrainLifecycleEvent evaluateCompletionLocked() {
    // Zero HTTP work alone is insufficient when a participant or startup action is unsafe.
    if (state != DrainState.DRAINING || registry.totalInFlight() != 0 || lastError != null)
      return null;
    List<ParticipantDrainStatus> statuses = participantStatuses();
    if (statuses.stream().allMatch(ParticipantDrainStatus::drained)) {
      DrainState previous = state;
      state = DrainState.DRAINED;
      if (timeoutTask != null) timeoutTask.cancel(false);
      return new DrainLifecycleEvent(
          previous, state, clock.instant(), "all safety conditions satisfied");
    }
    return null;
  }

  private void timeout() {
    DrainLifecycleEvent event = null;
    synchronized (lock) {
      if (state == DrainState.DRAINING) {
        DrainState previous = state;
        state = DrainState.TIMED_OUT;
        event = new DrainLifecycleEvent(previous, state, clock.instant(), "drain deadline reached");
      }
    }
    if (event != null) publish(event);
  }

  private List<ParticipantDrainStatus> participantStatuses() {
    List<ParticipantDrainStatus> result = new ArrayList<>();
    for (DrainParticipant participant : participants) {
      try {
        result.add(participant.status());
      } catch (RuntimeException ex) {
        result.add(
            new ParticipantDrainStatus(
                participant.name(),
                false,
                null,
                "status failed: " + ex.getMessage(),
                clock.instant()));
      }
    }
    return result;
  }

  private DrainStatus statusLocked() {
    var snapshot = registry.snapshot();
    var statuses = participantStatuses();
    return new DrainStatus(
        state,
        startedAt,
        deadline,
        snapshot.acceptingNewWork(),
        snapshot.byCategory(),
        statuses,
        state == DrainState.DRAINED,
        lastError);
  }

  private void publish(DrainLifecycleEvent event) {
    // Listeners run after state mutation and outside the coordinator monitor.
    listeners.forEach(listener -> listener.accept(event));
  }

  /** Stops the coordinator's timeout executor. */
  @Override
  public void close() {
    scheduler.shutdownNow();
  }
}
