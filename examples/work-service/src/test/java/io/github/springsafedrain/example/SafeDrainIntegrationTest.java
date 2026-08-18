package io.github.springsafedrain.example;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SafeDrainIntegrationTest {
  @Autowired TestRestTemplate http;

  @Test
  void acceptedWorkCompletesNewWorkIsRejectedAndServiceDrains() throws Exception {
    assertThat(status().get("state")).isEqualTo("ACTIVE");
    var pool = Executors.newSingleThreadExecutor();
    Future<ResponseEntity<Map>> workA =
        pool.submit(
            () ->
                http.postForEntity(
                    "/work",
                    Map.of("name", "report-generation", "processingDelayMs", 1500),
                    Map.class));
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> assertThat(((Map<?, ?>) status().get("inFlight")).get("http")).isEqualTo(1));
    ResponseEntity<Map> started =
        http.postForEntity("/actuator/safeDrain", Map.of("action", "DRAIN"), Map.class);
    assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(started.getBody().get("state")).isEqualTo("DRAINING");
    ResponseEntity<Map> workB =
        http.postForEntity(
            "/work", Map.of("name", "data-export", "processingDelayMs", 0), Map.class);
    assertThat(workB.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(workB.getBody().get("code")).isEqualTo("SERVICE_DRAINING");
    assertThat(http.getForEntity("/actuator/health", Map.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(status().get("state")).isEqualTo("DRAINING");
    assertThat(workA.get(5, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              Map<String, Object> s = status();
              assertThat(s.get("state")).isEqualTo("DRAINED");
              assertThat(s.get("safeToTerminate")).isEqualTo(true);
              assertThat((Map<?, ?>) s.get("inFlight")).isEmpty();
            });
    assertThat(http.getForEntity("/actuator/health", Map.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> resumed =
        http.postForEntity("/actuator/safeDrain", Map.of("action", "RESUME"), Map.class);
    assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resumed.getBody().get("state")).isEqualTo("ACTIVE");
    assertThat(resumed.getBody().get("acceptingNewWork")).isEqualTo(true);
    ResponseEntity<Map> resumedAgain =
        http.postForEntity("/actuator/safeDrain", Map.of("action", "RESUME"), Map.class);
    assertThat(resumedAgain.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resumedAgain.getBody().get("state")).isEqualTo("ACTIVE");
    ResponseEntity<Map> workC =
        http.postForEntity(
            "/work", Map.of("name", "cache-refresh", "processingDelayMs", 0), Map.class);
    assertThat(workC.getStatusCode()).isEqualTo(HttpStatus.OK);
    pool.shutdownNow();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> status() {
    return http.getForObject("/actuator/safeDrain", Map.class);
  }
}
