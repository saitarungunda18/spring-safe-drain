package io.github.ecssafedrain.core;

/** Lifecycle states for one drain operation within one application process. */
public enum DrainState {
  /** New work is admitted normally. */
  ACTIVE,
  /** Admission is closed while previously accepted work finishes. */
  DRAINING,
  /** Every safety condition is satisfied and the process may be terminated. */
  DRAINED,
  /** The drain deadline expired without satisfying every safety condition. */
  TIMED_OUT
}
