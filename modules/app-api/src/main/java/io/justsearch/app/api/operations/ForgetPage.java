/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.agent.api.registry.Nullable;
import io.justsearch.agent.api.registry.PreciseWire;
import java.util.List;

/** Bounded unfinished-intent projection; pagination never presents an omitted cohort as empty. */
public record ForgetPage(List<ForgetOutcome> items, boolean complete, @Nullable Long nextId)
    implements PreciseWire {
  public ForgetPage {
    items = List.copyOf(items);
    if (items.size() > 100 || complete != (nextId == null)
        || nextId != null && (nextId <= 0 || items.isEmpty())) {
      throw new IllegalArgumentException("Invalid unfinished forget page");
    }
  }
}
