/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.ExcludesService;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for {@code core.apply-excludes}.
 *
 * <p>Slice 3a-2-c continuation: LibraryView Apply Excludes button.
 * DESTRUCTIVE — walks every watched root and deletes already-indexed
 * documents whose paths match the configured exclude globs (Worker-delegated;
 * Head never touches Lucene).
 *
 * <p>Delegates to {@link ExcludesService#applyExcludes(boolean, EngineContext) applyExcludes(false)}
 * via a lazy supplier. HIGH-risk + Typed-confirm policy is enforced by the FE
 * ActionButton; the handler itself runs once dispatched (typed-confirm is FE
 * trust per slice 3a-1-2 §A.7; backend defense-in-depth is a follow-up).
 */
public final class ApplyExcludesHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(ApplyExcludesHandler.class);

  private final Supplier<ExcludesService> excludesSupplier;

  public ApplyExcludesHandler(Supplier<ExcludesService> excludesSupplier) {
    this.excludesSupplier = Objects.requireNonNull(excludesSupplier, "excludesSupplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    return ExcludesHandlerSupport.run(excludesSupplier, false, log, "ApplyExcludesHandler", engineContext);
  }
}
