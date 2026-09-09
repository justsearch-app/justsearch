/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.core.context.EngineContext;

import io.justsearch.core.dto.Query;
import io.justsearch.core.dto.Result;
import io.justsearch.core.search.SearchPort;
import java.util.List;
import java.util.Map;

/**
 * Tempdoc 519 §10 final-push: extracted from {@code HeadAssembly}. A no-op SearchPort
 * used when the Worker is not configured — search returns an empty result without throwing.
 * Wraps the test/offline path where no real search backend exists.
 */
public final class NoopSearchPort implements SearchPort {

  @Override
  public Result search(Query intent, EngineContext engineContext) {
    return new Result(List.of(), Map.of(), null, Map.of());
  }
}
