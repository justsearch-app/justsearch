/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.inference.telemetry.TransitionReason;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Activation forwarders for reconciler tests without a chat controller. {@link #switching} is the
 * adapter the reconciler's controller-less constructors built before Spec A step 12 deleted them:
 * an active activation runs the online switch on the forwarding thread; an inactive one detaches
 * an adopted external server, or runs the indexing switch.
 */
public final class TestActivationForwarders {
  private TestActivationForwarders() {}

  /** Stop primitive that may throw the checked exception the switch primitives do. */
  @FunctionalInterface
  public interface Detach {
    void detach() throws ModeTransitionException;
  }

  /** Reason-bearing engine switch. */
  @FunctionalInterface
  public interface Switch {
    void switchTo(TransitionReason reason) throws ModeTransitionException;
  }

  /**
   * The switching adapter. A null {@code onlineSwitch} or {@code indexingSwitch} falls back to
   * {@code control}'s {@code switchToOnlineMode}/{@code switchToIndexingMode}; a null {@code
   * detach} leaves an adopted external server alone.
   */
  public static RuntimeReconciler.ActivationForwarder switching(
      OnlineAiLifecycleControl control,
      BooleanSupplier externalAdoption,
      Detach detach,
      Switch onlineSwitch,
      Switch indexingSwitch) {
    return activation -> {
      try {
        if (activation.active()) {
          if (onlineSwitch != null) {
            onlineSwitch.switchTo(activation.reason());
          } else {
            control.switchToOnlineMode();
          }
        } else if (externalAdoption != null && externalAdoption.getAsBoolean()) {
          if (detach != null) {
            detach.detach();
          }
        } else if (indexingSwitch != null) {
          indexingSwitch.switchTo(activation.reason());
        } else {
          control.switchToIndexingMode();
        }
        return CompletableFuture.completedFuture(null);
      } catch (ModeTransitionException | RuntimeException failure) {
        return CompletableFuture.failedFuture(failure);
      }
    };
  }

  /** {@link #switching} with the control's own switches. */
  public static RuntimeReconciler.ActivationForwarder switching(
      OnlineAiLifecycleControl control, BooleanSupplier externalAdoption, Detach detach) {
    return switching(control, externalAdoption, detach, null, null);
  }
}
