/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.indexing.SchemaFields;
import java.util.HashMap;
import java.util.Map;

public final class EmbeddingBackfillOps {
  private EmbeddingBackfillOps() {}

  // About 1 MiB of UTF-16 text per parent batch. An oversized individual document is
  // admitted whole, then the batch stops: extraction policy remains the per-file limit.
  static final long MAX_PARENT_BATCH_CHARS = 512_000;

  public static Map<String, Object> computeEmbeddingFailureUpdate(int currentRetryCount) {
    int retryCount = currentRetryCount + 1;
    Map<String, Object> updates = new HashMap<>();
    updates.put(SchemaFields.EMBEDDING_RETRY_COUNT, String.valueOf(retryCount));
    if (retryCount >= SchemaFields.EMBEDDING_MAX_RETRIES) {
      updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_FAILED);
      updates.put(SchemaFields.VECTOR, null);
    }
    return updates;
  }

  public static Map<String, Object> computeChunkEmbeddingFailureUpdate(int currentRetryCount) {
    int retryCount = currentRetryCount + 1;
    Map<String, Object> updates = new HashMap<>();
    updates.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, String.valueOf(retryCount));
    if (retryCount >= SchemaFields.EMBEDDING_MAX_RETRIES) {
      updates.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_FAILED);
      updates.put(SchemaFields.CHUNK_VECTOR, null);
    }
    return updates;
  }

}
