/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing.runtime;

import java.util.List;
import java.util.Map;

/**
 * Provides SSOT-aligned commit metadata to be stamped on index commits.
 *
 * <p>Values are strongly typed (String, Integer, Boolean) to match schema types.
 *
 * <p>Stability: experimental
 */
public interface CommitMetadataSource {
  /** Build an immutable map of commit user data. Values should be deterministic. */
  Map<String, Object> build();

  /** Runtime-owned fingerprint inputs that cannot currently be resolved. */
  default List<String> indeterminateFingerprintInputs() {
    return List.of();
  }
}
