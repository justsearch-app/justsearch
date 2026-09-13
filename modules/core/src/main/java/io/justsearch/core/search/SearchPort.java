/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.search;

import io.justsearch.core.dto.Query;
import io.justsearch.core.dto.Result;
import io.justsearch.core.context.EngineContext;

/**
 * Core search port.
 *
 * <p>Stability: experimental
 */
public interface SearchPort {
  Result search(Query intent, EngineContext context);
}
