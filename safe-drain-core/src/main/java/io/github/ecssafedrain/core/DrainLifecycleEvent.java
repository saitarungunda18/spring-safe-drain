package io.github.ecssafedrain.core;

import java.time.Instant;

/** Immutable notification published after a coordinator state transition. */
public record DrainLifecycleEvent(
    DrainState previousState, DrainState state, Instant occurredAt, String detail) {}
