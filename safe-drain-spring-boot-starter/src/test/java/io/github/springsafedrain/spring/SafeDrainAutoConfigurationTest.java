package io.github.springsafedrain.spring;

import static org.assertj.core.api.Assertions.*;

import io.github.springsafedrain.core.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class SafeDrainAutoConfigurationTest {
  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(SafeDrainAutoConfiguration.class));

  @Test
  void disabledByDefault() {
    runner.run(c -> assertThat(c).doesNotHaveBean(InFlightWorkRegistry.class));
  }

  @Test
  void configuresWhenEnabled() {
    runner
        .withPropertyValues(
            "safe-drain.enabled=true",
            "safe-drain.http.enabled=false",
            "safe-drain.http.protected-paths=/work/**")
        .run(
            c -> {
              assertThat(c).hasSingleBean(InFlightWorkRegistry.class);
              assertThat(c).hasSingleBean(DrainCoordinator.class);
              assertThat(c).hasSingleBean(SafeDrainEndpoint.class);
            });
  }

  @Test
  void backsOffForUserRegistry() {
    var custom = new InFlightWorkRegistry();
    runner
        .withBean(InFlightWorkRegistry.class, () -> custom)
        .withPropertyValues("safe-drain.enabled=true", "safe-drain.http.enabled=false")
        .run(c -> assertThat(c.getBean(InFlightWorkRegistry.class)).isSameAs(custom));
  }

  @Test
  void rejectsInvalidConfiguration() {
    runner
        .withPropertyValues(
            "safe-drain.enabled=true", "safe-drain.http.enabled=false", "safe-drain.timeout=0s")
        .run(c -> assertThat(c).hasFailed());
  }
}
