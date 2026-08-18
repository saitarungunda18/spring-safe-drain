package io.github.springsafedrain.core;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class InFlightWorkRegistryTest {
  @Test
  void tokenLifecycleAndIdempotentClose() {
    var r = new InFlightWorkRegistry();
    var t = r.tryBegin("http").orElseThrow();
    assertThat(r.snapshot().byCategory()).containsEntry("http", 1L);
    t.close();
    t.close();
    assertThat(r.totalInFlight()).isZero();
  }

  @Test
  void closesAdmission() {
    var r = new InFlightWorkRegistry();
    r.closeAdmission();
    assertThat(r.tryBegin("http")).isEmpty();
    r.openAdmission();
    assertThat(r.tryBegin("http")).isPresent();
  }

  @Test
  void concurrentAcquisitionIsExact() throws Exception {
    var r = new InFlightWorkRegistry();
    var pool = Executors.newFixedThreadPool(8);
    var tokens = new CopyOnWriteArrayList<WorkToken>();
    var tasks =
        IntStream.range(0, 1000)
            .mapToObj(
                i ->
                    (Callable<Void>)
                        () -> {
                          tokens.add(r.tryBegin("http").orElseThrow());
                          return null;
                        })
            .toList();
    pool.invokeAll(tasks);
    assertThat(r.totalInFlight()).isEqualTo(1000);
    tokens.forEach(WorkToken::close);
    assertThat(r.totalInFlight()).isZero();
    pool.shutdownNow();
  }
}
