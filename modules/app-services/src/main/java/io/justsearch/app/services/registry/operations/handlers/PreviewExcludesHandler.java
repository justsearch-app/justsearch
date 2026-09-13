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
 * Handler for {@code core.preview-excludes}.
 *
 * <p>Slice 3a-2-c continuation: LibraryView Preview Excludes button. Walks
 * every watched root and counts, per pattern, how many already-indexed files
 * would be deleted by the configured exclude globs. Read-only; no deletion.
 *
 * <p>Delegates to {@link ExcludesService#applyExcludes(boolean, EngineContext) applyExcludes(true)}
 * via a lazy supplier (the service is late-bound by LocalApiServer after
 * IndexingController is constructed). The full result shape is forwarded as
 * {@code structuredData}, mirroring the pre-existing
 * {@code POST /api/indexing/excludes/apply?dryRun=true} response shape so the
 * FE consumer (LibraryView) can render the same preview UI without rework.
 */
public final class PreviewExcludesHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(PreviewExcludesHandler.class);

  private final Supplier<ExcludesService> excludesSupplier;

  public PreviewExcludesHandler(Supplier<ExcludesService> excludesSupplier) {
    this.excludesSupplier = Objects.requireNonNull(excludesSupplier, "excludesSupplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    return ExcludesHandlerSupport.run(excludesSupplier, true, log, "PreviewExcludesHandler", engineContext);
  }
}
