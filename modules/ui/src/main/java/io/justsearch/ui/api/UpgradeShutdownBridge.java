/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Late-bound bridge: the API starts before Worker connection, while the ordered shutdown owner can
 * only be assembled after all resources exist.
 */
public final class UpgradeShutdownBridge implements UpgradeShutdownAction {
  public enum Verification {
    ACCEPT,
    DEFER,
    REFUSE
  }

  @FunctionalInterface
  public interface Verifier {
    Verification verify(String preparationId, String shutdownNonce);
  }

  private final AtomicReference<UpgradeShutdownAction> delegate = new AtomicReference<>();
  private final AtomicReference<Verifier> verifier = new AtomicReference<>();

  public void install(UpgradeShutdownAction action) {
    if (!delegate.compareAndSet(null, Objects.requireNonNull(action))) {
      throw new IllegalStateException("upgrade shutdown action already installed");
    }
  }

  void installVerifier(Verifier candidate) {
    if (!verifier.compareAndSet(null, Objects.requireNonNull(candidate))) {
      throw new IllegalStateException("upgrade shutdown verifier already installed");
    }
  }

  public Verification verify(String preparationId, String shutdownNonce) {
    Verifier candidate = verifier.get();
    if (candidate == null) return Verification.REFUSE;
    Verification decision = candidate.verify(preparationId, shutdownNonce);
    return decision == null ? Verification.REFUSE : decision;
  }

  @Override
  public void shutdown(String preparationId, String shutdownNonce) {
    UpgradeShutdownAction action = delegate.get();
    if (action == null) {
      throw new IllegalStateException("upgrade shutdown action is not ready");
    }
    action.shutdown(preparationId, shutdownNonce);
  }
}
