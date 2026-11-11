/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.util.List;
import java.util.function.Consumer;

/** Lets producer mocks retain their scripted per-row storage faults and publication observers. */
public final class CoordinatorPublicationFixture {
  private CoordinatorPublicationFixture() {}

  public static boolean publish(IndexingCoordinator coordinator, IndexDocument parent,
      List<IndexDocument> children, Consumer<Boolean> parentPublished) {
    boolean accepted = coordinator.indexSingle(parent);
    if (parentPublished != null) parentPublished.accept(accepted);
    coordinator.deleteChunksForParentDocId((String) parent.fields().get(SchemaFields.DOC_ID));
    for (var child : children) coordinator.indexSingle(child);
    return accepted;
  }
}
