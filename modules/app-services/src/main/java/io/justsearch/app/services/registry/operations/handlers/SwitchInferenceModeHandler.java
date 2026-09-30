/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Handler for {@code core.switch-inference-mode} — a <b>temporary alias</b> superseded by
 * {@code core.set-chat-enabled} (tempdoc 737 §12b/§12d; retirement per §12d once the FE has fully
 * migrated off the {@code mode} vocabulary).
 *
 * <p>It maps the legacy {@code mode} argument onto the one runtime-authority intent write:
 * {@code online} → {@code chatEnabled=true}, {@code indexing} → {@code chatEnabled=false}, routed
 * through the SAME spec-write path as {@link SetChatEnabledHandler} (write via
 * {@link RuntimeSpecStore}, nudge {@link RuntimeReconciler#specChanged()}). It no longer calls
 * {@code BrainRuntimeService.switchInferenceMode} directly — the mode transition is now the
 * reconciler's business, so the alias cannot re-introduce the §3b circular denial. (The
 * {@code /api/inference/mode} REST endpoint still calls {@code BrainRuntimeServiceImpl
 * .switchInferenceMode} for any remaining internal callers.)
 *
 * <p>Args: {@code {"mode": "online" | "indexing"}}. Returns {@code structuredData} carrying the
 * now-current {@code chatEnabled} spec bit and the observed {@code engineState}.
 */
public final class SwitchInferenceModeHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(SwitchInferenceModeHandler.class);

  private final Supplier<RuntimeSpecStore> specStoreSupplier;
  private final Supplier<RuntimeReconciler> reconcilerSupplier;

  public SwitchInferenceModeHandler(
      Supplier<RuntimeSpecStore> specStoreSupplier,
      Supplier<RuntimeReconciler> reconcilerSupplier) {
    this.specStoreSupplier = Objects.requireNonNull(specStoreSupplier, "specStoreSupplier");
    this.reconcilerSupplier = Objects.requireNonNull(reconcilerSupplier, "reconcilerSupplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    boolean enabled;
    try {
      JsonNode root =
          HandlerJson.MAPPER.readTree(
              argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
      JsonNode modeNode = root.get("mode");
      if (modeNode == null || !modeNode.isTextual() || modeNode.asString().isBlank()) {
        return OperationResult.failure("Missing required arg: mode (use 'online' or 'indexing')");
      }
      String mode = modeNode.asString();
      if ("online".equalsIgnoreCase(mode)) {
        enabled = true;
      } else if ("indexing".equalsIgnoreCase(mode)) {
        enabled = false;
      } else {
        return OperationResult.failure(
            "Invalid mode. Use 'online' or 'indexing'", "INVALID_REQUEST", null, false);
      }
    } catch (Exception e) {
      return HandlerJson.invalidArgs(e);
    }
    return SetChatEnabledHandler.RuntimeIntentWrite.apply(
        specStoreSupplier, reconcilerSupplier, enabled, log);
  }
}
