/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

/**
 * Tempdoc 518 P2 — typed callback bridge for {@code /props} observations. Replaces the four
 * {@code Consumer<String>} / {@code Consumer<Integer>} / {@code Supplier<String>} /
 * {@code Supplier<Integer>} ports previously injected into {@link ServerPropsOps} for
 * model-id and context-tokens propagation.
 *
 * <p>The orchestrator-side implementation routes these callbacks through the {@link
 * TransitionRunner#mergeProps} CAS update and through any side-effects like model-swap detection
 * + persistence. The observer also reads back the current observed values (from the view atom)
 * so {@code ServerPropsOps} can compute external-adoption diagnostics without holding a back-
 * channel to the runtime's mutable state.
 */
interface PropsObserver {

  /**
   * Called when a non-blank model id is observed in a {@code /props} response. The orchestrator
   * routes this through model-swap detection + persistence + the view-atom merge.
   */
  void onModelIdObserved(String modelId, LlamaServerConfigContext context);

  /**
   * Called when a positive context-tokens value is observed in a {@code /props} response. The
   * orchestrator routes this through the view-atom merge.
   */
  void onContextTokensObserved(int contextTokens);

  /** Current observed model id, or {@code null} if none has been observed yet. */
  String observedModelId();

  /** Current observed context tokens, or {@code null} if none has been observed yet. */
  Integer observedContextTokens();
}
