package io.github.ecssafedrain.core;

/** A close-once handle representing one unit of work accepted by the registry. */
public interface WorkToken extends AutoCloseable {
  /** Completes the registered work; implementations must tolerate duplicate calls. */
  @Override
  void close();
}
