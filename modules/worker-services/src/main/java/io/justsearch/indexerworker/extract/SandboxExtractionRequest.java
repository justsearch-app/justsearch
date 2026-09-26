/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

/** Versioned request sent from the Worker extraction parent to a sandbox child process. */
public record SandboxExtractionRequest(
    int schemaVersion, String requestId, String path, TikaExtractionPolicy policy, OcrRoutingConfig ocrConfig) {
  public static final int CURRENT_SCHEMA_VERSION = 2;
  static final int MAX_REQUEST_ID_CHARS = 96;

  public SandboxExtractionRequest {
    if (schemaVersion != CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported sandbox request schema");
    }
    requireRequestId(requestId);
    policy = policy == null ? TikaExtractionPolicy.defaults() : policy;
    ocrConfig = ocrConfig == null ? OcrRoutingConfig.disabled() : ocrConfig;
  }
  static void requireRequestId(String requestId) {
    if (requestId == null || requestId.isBlank() || requestId.length() > MAX_REQUEST_ID_CHARS) {
      throw new IllegalArgumentException("Sandbox requestId must contain 1-96 characters");
    }
  }
}
