/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.justsearch.app.api.settings.CompositionV2;
import java.util.Set;

/**
 * Durable non-content outcome. Rich handler responses are never serialized into operations.db.
 * Identifiers are compact tokens; the structured exceptions are a validated physical composition
 * decision needed for settings replay and, for a completed folder removal, the folder it removed
 * (so every window, and a later session, can offer to add it back). Prose and other handler
 * payloads have no slot here. Unit counts come from the committed checkpoint, not the immediate
 * response. Both structured fields are optional: rows written before they existed read as null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OperationReceipt(String code, String executionId, CompositionV2 composition,
    RemovedFolder removedFolder) {
  /** The handler-result key under which a removal hands its {@link RemovedFolder} to the receipt. */
  public static final String REMOVED_FOLDER_KEY = "removedFolder";

  /**
   * The watched folder a successful {@code core.remove-watched-root} removed: its absolute path and
   * the collection it was watched under ({@code null} for the default collection).
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record RemovedFolder(String path, String collection) {
    private static final int MAX_PATH_LENGTH = 4096;
    private static final int MAX_COLLECTION_LENGTH = 256;

    public RemovedFolder {
      if (!printable(path, MAX_PATH_LENGTH)) {
        throw new IllegalArgumentException("Removed folder path must be a bounded, printable path");
      }
      if (collection != null && !printable(collection, MAX_COLLECTION_LENGTH)) {
        throw new IllegalArgumentException("Removed folder collection must be bounded and printable");
      }
    }

    /** The removal record for a folder, or empty when it cannot be recorded (never fails a removal). */
    public static java.util.Optional<RemovedFolder> of(String path, String collection) {
      String named = collection == null || collection.isBlank() || "default".equals(collection) ? null : collection;
      if (!printable(path, MAX_PATH_LENGTH) || (named != null && !printable(named, MAX_COLLECTION_LENGTH))) {
        return java.util.Optional.empty();
      }
      return java.util.Optional.of(new RemovedFolder(path, named));
    }

    private static boolean printable(String value, int max) {
      return value != null && !value.isBlank() && value.length() <= max
          && value.chars().noneMatch(Character::isISOControl);
    }
  }

  private static final Set<String> COMPOSITION_REASONS = Set.of(
      "candidate_fits_free_device_memory",
      "source_holds_no_device_memory",
      "candidate_exceeds_releasable_device_memory",
      "free_device_memory_unknown",
      "source_releasable_device_memory_unknown",
      "candidate_fits_after_source_release");

  public OperationReceipt(String code, String executionId) {
    this(code, executionId, null, null);
  }

  public OperationReceipt(String code, String executionId, CompositionV2 composition) {
    this(code, executionId, composition, null);
  }

  public OperationReceipt {
    if (!io.justsearch.agent.api.registry.OperationResult.isDurableOutcomeCode(code)) {
      throw new IllegalArgumentException("Outcome code must be a bounded token");
    }
    if (executionId != null && !executionId.matches("[A-Za-z0-9_.:/-]{1,128}")) {
      throw new IllegalArgumentException("Execution id must be a bounded identifier");
    }
    if (composition != null && (composition.reason() == null
        || !COMPOSITION_REASONS.contains(composition.reason()))) {
      throw new IllegalArgumentException("Composition reason must be a durable decision token");
    }
  }
}
