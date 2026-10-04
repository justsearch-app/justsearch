/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import java.util.List;
import java.util.Map;

/** Versioned response returned by a sandbox child process. */
public record SandboxExtractionResponse(
    int schemaVersion,
    String requestId,
    ExtractionStatus status,
    String content,
    String title,
    String mimeType,
    String author,
    Map<String, String> frontmatterMetadata,
    String policyId,
    String parserId,
    boolean truncated,
    List<String> warnings,
    int embeddedResourceCount,
    int maxEmbeddedDepth,
    String visualExtractionEvidenceJson,
    String errorMessage,
    String reasonCode) {
  public static final int CURRENT_SCHEMA_VERSION = SandboxExtractionRequest.CURRENT_SCHEMA_VERSION;
  static final int MAX_IDENTIFIER_CHARS = 96;
  static final int MAX_ERROR_MESSAGE_CHARS = 512;
  static final int MAX_REASON_CODE_CHARS = 512;

  public SandboxExtractionResponse {
    SandboxExtractionRequest.requireRequestId(requestId);
    requireBounded("policyId", policyId, MAX_IDENTIFIER_CHARS);
    requireBounded("parserId", parserId, MAX_IDENTIFIER_CHARS);
    errorMessage = sanitize(errorMessage, MAX_ERROR_MESSAGE_CHARS);
    reasonCode = sanitize(reasonCode, MAX_REASON_CODE_CHARS);
  }

  public static SandboxExtractionResponse fromArtifact(String requestId, ExtractionArtifact artifact) {
    ExtractionResult result = artifact.result();
    return new SandboxExtractionResponse(
        CURRENT_SCHEMA_VERSION,
        requestId,
        artifact.status(),
        result.content(),
        result.title(),
        result.mimeType(),
        result.author(),
        result.frontmatterMetadata(),
        artifact.policyId(),
        artifact.parserId(),
        artifact.truncated(),
        artifact.warnings(),
        artifact.embeddedResourceCount(),
        artifact.maxEmbeddedDepth(),
        artifact.visualExtractionEvidenceJson(),
        null,
        null);
  }

  public static SandboxExtractionResponse failed(
      String requestId, ExtractionStatus status, TikaExtractionPolicy policy, String parserId,
      String errorMessage, String reasonCode) {
    return new SandboxExtractionResponse(
        CURRENT_SCHEMA_VERSION,
        requestId,
        status,
        "",
        null,
        "application/octet-stream",
        null,
        Map.of(),
        policy == null ? TikaExtractionPolicy.defaults().policyId() : policy.policyId(),
        parserId,
        false,
        List.of(),
        0,
        0,
        null,
        errorMessage,
        reasonCode);
  }

  public ExtractionArtifact toArtifact() {
    ExtractionResult result =
        new ExtractionResult(content, title, mimeType, author, frontmatterMetadata);
    return new ExtractionArtifact(
        status,
        result,
        policyId,
        parserId,
        truncated,
        warnings,
        embeddedResourceCount,
        maxEmbeddedDepth,
        visualExtractionEvidenceJson);
  }

  private static void requireBounded(String name, String value, int maxChars) {
    if (value != null && value.length() > maxChars) {
      throw new IllegalArgumentException(name + " exceeds " + maxChars + " UTF-16 code units");
    }
  }

  private static String sanitize(String value, int maxChars) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String oneLine = value.replaceAll("[\\r\\n\\t]+", " ").trim();
    return oneLine.length() <= maxChars ? oneLine : oneLine.substring(0, maxChars);
  }
}
