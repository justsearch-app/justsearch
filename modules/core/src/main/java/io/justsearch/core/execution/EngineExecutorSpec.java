/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.execution;

import java.util.Objects;

/** Immutable description of one logical executor owner. */
public record EngineExecutorSpec(
    String name,
    Kind kind,
    Mode mode,
    Integer threadCount,
    Integer queueCapacity,
    int maxInstances) {

  public enum Kind { FOREGROUND, BACKGROUND }

  public enum Mode { PLATFORM, SCHEDULED, VIRTUAL }

  public EngineExecutorSpec {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(mode, "mode");
    if (name.isBlank()) throw new IllegalArgumentException("Executor name must not be blank");
    if (maxInstances < 1) throw new IllegalArgumentException("maxInstances must be positive");
    if (mode == Mode.VIRTUAL) {
      if (threadCount != null || queueCapacity != null) {
        throw new IllegalArgumentException("Virtual executors do not declare thread or queue bounds");
      }
    } else {
      if (threadCount == null || threadCount < 1) {
        throw new IllegalArgumentException("threadCount must be positive");
      }
      if (queueCapacity == null || queueCapacity < 0
          || (mode == Mode.SCHEDULED && queueCapacity == 0)) {
        throw new IllegalArgumentException("queueCapacity is invalid for " + mode);
      }
    }
  }

  public static EngineExecutorSpec virtual(String name, Kind kind, int maxInstances) {
    return new EngineExecutorSpec(name, kind, Mode.VIRTUAL, null, null, maxInstances);
  }
}
