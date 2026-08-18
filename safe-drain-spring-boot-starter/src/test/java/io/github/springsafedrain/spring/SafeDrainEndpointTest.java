package io.github.springsafedrain.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.springsafedrain.core.DrainCoordinator;
import io.github.springsafedrain.core.DrainState;
import io.github.springsafedrain.core.InFlightWorkRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SafeDrainEndpointTest {
  @Test
  void delegatesReadDrainAndResumeActions() {
    var registry = new InFlightWorkRegistry();
    var token = registry.tryBegin("http").orElseThrow();
    try (var coordinator = new DrainCoordinator(registry, List.of(), Duration.ofSeconds(1))) {
      var endpoint = new SafeDrainEndpoint(coordinator);
      assertThat(endpoint.status().state()).isEqualTo(DrainState.ACTIVE);
      assertThat(endpoint.changeState(SafeDrainAction.DRAIN).state())
          .isEqualTo(DrainState.DRAINING);
      assertThat(endpoint.changeState(SafeDrainAction.RESUME).state()).isEqualTo(DrainState.ACTIVE);
      assertThat(endpoint.changeState(SafeDrainAction.RESUME).state()).isEqualTo(DrainState.ACTIVE);
    } finally {
      token.close();
    }
  }
}
