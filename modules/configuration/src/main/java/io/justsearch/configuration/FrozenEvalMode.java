/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

/** Internal launch admission, captured by each physical owner; never a persisted user setting. */
public final class FrozenEvalMode {
  private FrozenEvalMode() {}

  public static boolean requested() {
    boolean frozen = EnvRegistry.FROZEN_EVAL.getBoolean(false);
    if (frozen && !EnvRegistry.EVAL_MODE.getBoolean(false)) {
      throw new IllegalStateException("Frozen index launch requires eval mode");
    }
    return frozen;
  }
}
