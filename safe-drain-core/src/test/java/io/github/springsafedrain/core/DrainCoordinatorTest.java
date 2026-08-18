package io.github.springsafedrain.core;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class DrainCoordinatorTest {
  @Test
  void resumeIsIdempotentWhileActive() {
    var registry = new InFlightWorkRegistry();
    try (var coordinator = new DrainCoordinator(registry, List.of(), Duration.ofSeconds(5))) {
      DrainStatus status = coordinator.abort();

      assertThat(status.state()).isEqualTo(DrainState.ACTIVE);
      assertThat(status.acceptingNewWork()).isTrue();
      assertThat(status.safeToTerminate()).isFalse();
    }
  }

  @Test
  void drainsImmediatelyWithoutWork() {
    try (var c =
        new DrainCoordinator(new InFlightWorkRegistry(), List.of(), Duration.ofSeconds(1))) {
      assertThat(c.startDrain().state()).isEqualTo(DrainState.DRAINED);
      assertThat(c.status().safeToTerminate()).isTrue();
    }
  }

  @Test
  void waitsForAcceptedWork() {
    var r = new InFlightWorkRegistry();
    var t = r.tryBegin("http").orElseThrow();
    try (var c = new DrainCoordinator(r, List.of(), Duration.ofSeconds(1))) {
      assertThat(c.startDrain().state()).isEqualTo(DrainState.DRAINING);
      t.close();
      await().untilAsserted(() -> assertThat(c.status().state()).isEqualTo(DrainState.DRAINED));
    }
  }

  @Test
  void abortDuringDrainResumesAdmission() {
    var registry = new InFlightWorkRegistry();
    var token = registry.tryBegin("http").orElseThrow();
    try (var coordinator = new DrainCoordinator(registry, List.of(), Duration.ofSeconds(1))) {
      coordinator.startDrain();
      assertThat(coordinator.abort().state()).isEqualTo(DrainState.ACTIVE);
      assertThat(registry.tryBegin("http")).isPresent();
    } finally {
      token.close();
    }
  }

  @Test
  void resumeAfterDrainedReopensAdmission() {
    var registry = new InFlightWorkRegistry();
    try (var coordinator = new DrainCoordinator(registry, List.of(), Duration.ofSeconds(1))) {
      assertThat(coordinator.startDrain().state()).isEqualTo(DrainState.DRAINED);
      assertThat(coordinator.abort().state()).isEqualTo(DrainState.ACTIVE);
      assertThat(coordinator.status().safeToTerminate()).isFalse();
      assertThat(registry.isAcceptingNewWork()).isTrue();
      assertThat(registry.tryBegin("http")).isPresent();
    }
  }

  @Test
  void participantNotificationCompletesDrain() {
    var drained = new java.util.concurrent.atomic.AtomicBoolean();
    var participant =
        new DrainParticipant() {
          public String name() {
            return "outbox";
          }

          public void stopAcceptingNewWork() {}

          public ParticipantDrainStatus status() {
            return new ParticipantDrainStatus(name(), drained.get(), 0L, "test", Instant.now());
          }

          public void resume() {}
        };
    try (var coordinator =
        new DrainCoordinator(
            new InFlightWorkRegistry(), List.of(participant), Duration.ofSeconds(1))) {
      assertThat(coordinator.startDrain().state()).isEqualTo(DrainState.DRAINING);
      drained.set(true);
      coordinator.requestEvaluation();
      assertThat(coordinator.status().state()).isEqualTo(DrainState.DRAINED);
    }
  }

  @Test
  void timesOutAndCanAbort() {
    var r = new InFlightWorkRegistry();
    var t = r.tryBegin("http").orElseThrow();
    try (var c = new DrainCoordinator(r, List.of(), Duration.ofMillis(30))) {
      c.startDrain();
      await().untilAsserted(() -> assertThat(c.status().state()).isEqualTo(DrainState.TIMED_OUT));
      assertThat(c.abort().state()).isEqualTo(DrainState.ACTIVE);
      assertThat(r.isAcceptingNewWork()).isTrue();
    } finally {
      t.close();
    }
  }

  @Test
  void concurrentStartsShareOperation() throws Exception {
    try (var c =
        new DrainCoordinator(new InFlightWorkRegistry(), List.of(), Duration.ofSeconds(1))) {
      var pool = Executors.newFixedThreadPool(8);
      var results =
          pool.invokeAll(
              java.util.stream.IntStream.range(0, 50)
                  .mapToObj(i -> (Callable<DrainStatus>) c::startDrain)
                  .toList());
      assertThat(results)
          .allSatisfy(f -> assertThat(f.get().state()).isEqualTo(DrainState.DRAINED));
      pool.shutdownNow();
    }
  }

  @Test
  void participantFailureBlocksDrain() {
    var p =
        new DrainParticipant() {
          public String name() {
            return "bad";
          }

          public void stopAcceptingNewWork() {
            throw new IllegalStateException("boom");
          }

          public ParticipantDrainStatus status() {
            return new ParticipantDrainStatus(name(), true, 0L, "ok", Instant.now());
          }

          public void resume() {}
        };
    try (var c =
        new DrainCoordinator(new InFlightWorkRegistry(), List.of(p), Duration.ofMillis(30))) {
      assertThat(c.startDrain().lastError()).contains("boom");
      await().untilAsserted(() -> assertThat(c.status().state()).isEqualTo(DrainState.TIMED_OUT));
    }
  }

  @Test
  void drainCanBeAbortedAndResumeRemainsIdempotent() {
    try (var c =
        new DrainCoordinator(new InFlightWorkRegistry(), List.of(), Duration.ofSeconds(1))) {
      assertThat(c.abort().state()).isEqualTo(DrainState.ACTIVE);
      c.startDrain();
      assertThat(c.abort().state()).isEqualTo(DrainState.ACTIVE);
      assertThat(c.abort().state()).isEqualTo(DrainState.ACTIVE);
    }
  }
}
