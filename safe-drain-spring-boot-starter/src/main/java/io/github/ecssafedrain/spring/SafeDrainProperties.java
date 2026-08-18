package io.github.ecssafedrain.spring;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validated configuration bound from the {@code safe-drain} property namespace. */
@Validated
@ConfigurationProperties("safe-drain")
public class SafeDrainProperties {
  private boolean enabled;

  @NotNull private Duration timeout = Duration.ofSeconds(30);

  @Valid @NotNull private Http http = new Http();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    enabled = v;
  }

  public Duration getTimeout() {
    return timeout;
  }

  public void setTimeout(Duration v) {
    if (v == null) {
      throw new IllegalArgumentException("timeout must not be null");
    }
    if (v.isZero() || v.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    timeout = v;
  }

  public Http getHttp() {
    return http;
  }

  public void setHttp(Http v) {
    http = v;
  }

  /** HTTP admission and rejection settings. */
  @Validated
  public static class Http {
    private boolean enabled = true;
    @NotEmpty private List<String> protectedPaths = new ArrayList<>(List.of("/api/**"));
    private List<String> excludedPaths = new ArrayList<>(List.of("/actuator/**"));
    @Positive private int retryAfterSeconds = 30;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean v) {
      enabled = v;
    }

    public List<String> getProtectedPaths() {
      return protectedPaths;
    }

    public void setProtectedPaths(List<String> v) {
      protectedPaths = v;
    }

    public List<String> getExcludedPaths() {
      return excludedPaths;
    }

    public void setExcludedPaths(List<String> v) {
      excludedPaths = v;
    }

    public int getRetryAfterSeconds() {
      return retryAfterSeconds;
    }

    public void setRetryAfterSeconds(int v) {
      retryAfterSeconds = v;
    }
  }
}
