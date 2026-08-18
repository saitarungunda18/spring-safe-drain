package io.github.ecssafedrain.spring;

/** Commands accepted by the safe-drain management endpoint. */
public enum SafeDrainAction {
  /** Close admission and wait for already accepted work. */
  DRAIN,
  /** Return the instance to active admission. */
  RESUME
}
