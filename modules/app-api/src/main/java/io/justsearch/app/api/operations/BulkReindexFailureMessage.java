/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

/** Self-contained read-time explanations; durable receipts and failure reasons stay compact codes. */
public final class BulkReindexFailureMessage {
  private BulkReindexFailureMessage() {}

  /**
   * Shared refusal codes also occur on incremental ingestion, where these assurances do not apply.
   * A refusal does not establish that the source index was healthy, or that search is online now.
   */
  public static String forOperation(String operationRef, String code) {
    if (operationRef == null || code == null) return null;
    if (!operationRef.equals("core.bulk-reindex") && !operationRef.equals("core.rebuild-index")
        && !operationRef.equals("core.activate-installed-models")) return null;
    String sentence = switch (code) {
      case "cancelled" -> "The rebuild or migration was cancelled before activation; this request did not replace the existing index, and you can start a new rebuild when ready.";
      case "RECOVERY_AUTHORIZATION_REFUSED" -> "The rebuild or migration could not validate its permissions or prepared model files; this request did not replace the existing index, so review access and model files before starting a new rebuild.";
      case "RECOVERY_SCOPE_REFUSED" -> "The rebuild or migration could not continue because its recorded source scope could not be confirmed; this request did not replace the existing index, so review source access before starting a new rebuild.";
      case "RECOVERY_BINDING_INVALID" -> "The rebuild or migration could not continue because its saved request could not be validated; this request did not replace the existing index, so start a new rebuild using the current settings.";
      // This code also covers a fenced runtime or a changed serving generation: do not promise
      // that a particular old generation is still serving, or guess that a restart caused it.
      case "BULK_GENERATION_REFUSED" -> "The rebuild or migration could not safely start or resume the new index; check index health and current settings before starting a new rebuild.";
      case "BULK_CAPTURE_FAILED" -> "The rebuild or migration could not finish collecting files; this request did not replace the existing index, so check source folder availability before starting a new rebuild.";
      case "INGEST_RECOVERY_ATTEMPTS_EXHAUSTED" -> "The rebuild or migration stopped after too many recovery attempts; this request did not replace the existing index, so check index health before starting a new rebuild.";
      case "ACTIVATION_PRECOMMIT_REFUSED" -> "The rebuild or migration stopped before activating the new index and settings; this request did not replace the existing index, so review settings and index health before retrying activation.";
      default -> null;
    };
    return sentence == null ? null : sentence + " (Code: " + code + ")";
  }
}
