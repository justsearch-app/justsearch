/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.Map;

/**
 * GPU runtime-variant activation surface exposed to the AppFacade.
 *
 * <p>Slice 3a-2-c continuation (BrainRuntime variant cluster): backs
 * {@code core.activate-runtime-variant} (MEDIUM, requires variantId) and
 * {@code core.deactivate-runtime-variant} (MEDIUM). Production wiring:
 * {@code RuntimeVariantServiceImpl} (app-services) implements this interface
 * and is composed by {@code ServicePhase}.
 *
 * <p>Both methods return an immediate runtime status map plus the owner's actual completion.
 * The map preserves the existing HTTP/operation response shape; it is not a terminal outcome.
 *
 * <p>Stability: stable (API contract).
 */
public interface RuntimeVariantService {

  /** One attempt's snapshot and completion, never a lookup of mutable global status. */
  record Attempt(Map<String, Object> status,
      java.util.concurrent.CompletionStage<AiRuntimeActivationStatus> completion) {
    public Attempt {
      status = Map.copyOf(status);
      java.util.Objects.requireNonNull(completion, "completion");
    }
  }

  /**
   * Begin activation of a runtime variant (e.g., a CUDA pack). Validates
   * that activation is allowed under enterprise policy (Online AI enabled,
   * GPU acceleration enabled), starts the activation lifecycle in the
   * background, and returns the post-start status snapshot.
   *
   * @param variantId the variant id; must be non-null and non-blank
   * @return immediate status and owner completion
   * @throws IllegalArgumentException for null/blank variantId
   * @throws IllegalStateException when an activation is already running
   * @throws Exception on policy denial or activation start failure
   */
  Attempt activate(String variantId) throws Exception;

  /**
   * Begin deactivation of the currently-active runtime variant and return
   * the post-start status snapshot.
   *
   * @return immediate status and owner completion
   * @throws IllegalStateException when an activation/deactivation is
   *     already running
   * @throws Exception on deactivation start failure
   */
  Attempt deactivate() throws Exception;

}
