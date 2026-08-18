package io.github.springsafedrain.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe admission gate and in-flight counter shared by workload adapters. */
public final class InFlightWorkRegistry {
  // Admission checks and increments share this lock so drain cannot race with acceptance.
  private final Object lock = new Object();
  private final Map<String, Long> counts = new HashMap<>();
  private final CopyOnWriteArrayList<Runnable> zeroListeners = new CopyOnWriteArrayList<>();
  private boolean accepting = true;

  /**
   * Attempts to admit and count one unit of work.
   *
   * @return a token when admitted, or an empty value after admission has closed
   */
  public Optional<WorkToken> tryBegin(String category) {
    Objects.requireNonNull(category, "category");
    if (category.isBlank()) throw new IllegalArgumentException("category must not be blank");
    synchronized (lock) {
      if (!accepting) return Optional.empty();
      counts.merge(category, 1L, Long::sum);
    }
    return Optional.of(new Token(category));
  }

  /** Atomically prevents future calls to {@link #tryBegin(String)} from succeeding. */
  public void closeAdmission() {
    synchronized (lock) {
      accepting = false;
    }
  }

  /** Reopens admission after a resume operation. */
  public void openAdmission() {
    synchronized (lock) {
      accepting = true;
    }
  }

  /** Returns whether new work may currently acquire a token. */
  public boolean isAcceptingNewWork() {
    synchronized (lock) {
      return accepting;
    }
  }

  /** Returns the total number of tokens that have not yet been closed. */
  public long totalInFlight() {
    return snapshot().total();
  }

  /** Returns an immutable point-in-time registry snapshot. */
  public InFlightSnapshot snapshot() {
    synchronized (lock) {
      return new InFlightSnapshot(accepting, counts);
    }
  }

  /** Registers a callback invoked whenever completion makes the registry empty. */
  public void onZero(Runnable listener) {
    zeroListeners.add(Objects.requireNonNull(listener));
  }

  private void complete(String category) {
    boolean zero;
    synchronized (lock) {
      long current = counts.getOrDefault(category, 0L);
      if (current <= 0) throw new IllegalStateException("work counter underflow: " + category);
      if (current == 1) counts.remove(category);
      else counts.put(category, current - 1);
      zero = counts.isEmpty();
    }
    // Invoke callbacks outside the registry lock to avoid lock-order deadlocks with coordinators.
    if (zero) zeroListeners.forEach(Runnable::run);
  }

  private final class Token implements WorkToken {
    private final String category;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Token(String category) {
      this.category = category;
    }

    @Override
    public void close() {
      // Servlet async completion may signal more than once; decrement exactly once.
      if (closed.compareAndSet(false, true)) complete(category);
    }
  }
}
