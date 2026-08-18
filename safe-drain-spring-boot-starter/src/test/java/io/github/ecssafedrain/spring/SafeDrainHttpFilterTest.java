package io.github.ecssafedrain.spring;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ecssafedrain.core.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class SafeDrainHttpFilterTest {
  @Test
  void protectsConfiguredPathAndReturnsJsonAfterDrain() throws Exception {
    var r = new InFlightWorkRegistry();
    try (var c = new DrainCoordinator(r, List.of(), Duration.ofSeconds(1))) {
      var p = new SafeDrainProperties.Http();
      p.setProtectedPaths(List.of("/work/**"));
      var f = new SafeDrainHttpFilter(r, c, p, new ObjectMapper());
      c.startDrain();
      var req = new MockHttpServletRequest("POST", "/work/new");
      var res = new MockHttpServletResponse();
      f.doFilter(req, res, new MockFilterChain());
      assertThat(res.getStatus()).isEqualTo(503);
      assertThat(res.getContentType()).startsWith("application/json");
      assertThat(res.getContentAsString()).contains("SERVICE_DRAINING");
      assertThat(res.getHeader("Retry-After")).isEqualTo("30");
    }
  }

  @Test
  void excludedActuatorPathRemainsAccessible() throws Exception {
    var r = new InFlightWorkRegistry();
    try (var c = new DrainCoordinator(r, List.of(), Duration.ofSeconds(1))) {
      var p = new SafeDrainProperties.Http();
      p.setProtectedPaths(List.of("/**"));
      p.setExcludedPaths(List.of("/actuator/**"));
      var f = new SafeDrainHttpFilter(r, c, p, new ObjectMapper());
      c.startDrain();
      var res = new MockHttpServletResponse();
      f.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), res, new MockFilterChain());
      assertThat(res.getStatus()).isEqualTo(200);
    }
  }
}
