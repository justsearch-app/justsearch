/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.agent.api.registry.PreciseWire;
import java.util.List;

/** Authoritative retained-import inventory, independent of published index rows. */
public record InterruptedImportsView(
    List<RecordedIngestionService.InterruptedImport> imports) implements PreciseWire {
  public InterruptedImportsView { imports = List.copyOf(imports); }
}
