package io.github.ecssafedrain.core;

/** Extension point for non-HTTP workloads that must participate in a drain decision. */
public interface DrainParticipant {
  /** Returns a stable, human-readable participant name. */
  String name();

  /** Stops this participant from accepting new work. */
  void stopAcceptingNewWork();

  /** Returns the participant's current drain condition. */
  ParticipantDrainStatus status();

  /** Reopens participant admission after a resume command. */
  void resume();
}
