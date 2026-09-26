/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.indexing;

import java.io.IOException;
import java.util.function.Consumer;

/** Source-owned enumeration of a complete no-file projection set for a candidate generation. */
public interface ProjectionSeedSource {
  /** Stable source identity, shared by every emitted projection and retained across Engine boots. */
  String sourceId();

  /**
   * Streams current live documents and retained deletion revisions to a bounded sink. A normal
   * return certifies completeness; locked, unreadable or incomplete source state must throw.
   * The source owns mutation revision order and must reject delayed stale submissions at its port.
   */
  void enumerate(Consumer<AcceptedProjection> sink) throws IOException;
}
