package io.github.springsafedrain.spring;

import io.github.springsafedrain.core.*;
import org.springframework.boot.actuate.endpoint.annotation.*;

/** Actuator adapter exposing status and lifecycle commands for one application instance. */
@Endpoint(id = "safeDrain")
public final class SafeDrainEndpoint {
  private final DrainCoordinator coordinator;

  public SafeDrainEndpoint(DrainCoordinator c) {
    coordinator = c;
  }

  /** Returns the latest drain status without changing lifecycle state. */
  @ReadOperation
  public DrainStatus status() {
    return coordinator.status();
  }

  /** Applies an idempotent {@link SafeDrainAction} and returns the resulting status. */
  @WriteOperation
  public DrainStatus changeState(SafeDrainAction action) {
    if (action == null) throw new IllegalArgumentException("action is required");
    return switch (action) {
      case DRAIN -> coordinator.startDrain();
      case RESUME -> coordinator.abort();
    };
  }
}
