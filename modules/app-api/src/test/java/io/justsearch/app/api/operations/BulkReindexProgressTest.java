/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BulkReindexProgressTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC);
  private static final String CANONICAL_INPUTS = "{\"fixture\":\"synthetic-index-target\"}";
  private static final String HASH_A = "a".repeat(64);
  private static final String HASH_B = "b".repeat(64);
  private static final String HASH_C = "c".repeat(64);

  @Test
  void projectsLifecycleCursorsAndSettledUnitCounts() {
    String generation = generationId();
    var capturing = new BulkReindexProgress(generation, target(), BulkReindexProgress.Phase.CAPTURING,
        null, null);
    assertEquals("capturing", BulkReindexProgress.Phase.CAPTURING.wire());
    assertEquals("bulk:capturing", capturing.cursor());
    assertEquals(0, capturing.unitsCompleted());
    assertEquals(0, capturing.unitsFailed());

    var capture = new BulkReindexProgress.Capture(HASH_A, 5);
    var building = new BulkReindexProgress(generation, target(), BulkReindexProgress.Phase.BUILDING,
        capture, null);
    assertEquals("building", BulkReindexProgress.Phase.BUILDING.wire());
    assertEquals("bulk:building", building.cursor());
    assertEquals(0, building.unitsCompleted());
    assertEquals(0, building.unitsFailed());

    var settled = new BulkReindexProgress(generation, target(), BulkReindexProgress.Phase.SETTLED,
        capture, settlement(3, HASH_B, List.of(gap("unit-1"), gap("unit-2")), List.of()));
    assertEquals("settled", BulkReindexProgress.Phase.SETTLED.wire());
    assertEquals("bulk-receipt:3:" + HASH_B, settled.cursor());
    assertEquals(3, settled.unitsCompleted());
    assertEquals(2, settled.unitsFailed());
  }

  @Test
  void acceptsACompletedEmptyPlanAndSettlement() {
    var empty = new BulkReindexProgress(generationId(), target(), BulkReindexProgress.Phase.SETTLED,
        new BulkReindexProgress.Capture(HASH_A, 0), settlement(1, HASH_B, List.of(), List.of()));

    assertEquals(0, empty.unitsCompleted());
    assertEquals(0, empty.unitsFailed());
    assertEquals("bulk-receipt:1:" + HASH_B, empty.cursor());
  }

  @Test
  void rejectsInvalidGenerationAndPhaseEvidenceCombinations() {
    String generation = generationId();
    var capture = new BulkReindexProgress.Capture(HASH_A, 1);
    var settled = settlement(1, HASH_B, List.of(), List.of());

    assertThrows(IllegalArgumentException.class,
        () -> new BulkReindexProgress("generation-1", target(),
            BulkReindexProgress.Phase.CAPTURING, null, null));
    String validKey = OperationKeys.generate(CLOCK);
    String uppercaseKey = validKey.substring(0, 24) + "a" + validKey.substring(25);
    assertThrows(IllegalArgumentException.class,
        () -> new BulkReindexProgress("g-" + uppercaseKey.toUpperCase(java.util.Locale.ROOT),
            target(), BulkReindexProgress.Phase.CAPTURING, null, null));
    String versionFourKey = validKey.substring(0, 14) + "4" + validKey.substring(15);
    assertThrows(IllegalArgumentException.class,
        () -> new BulkReindexProgress("g-" + versionFourKey,
            target(), BulkReindexProgress.Phase.CAPTURING, null, null));
    assertThrows(NullPointerException.class,
        () -> new BulkReindexProgress(generation, null, BulkReindexProgress.Phase.CAPTURING, null, null));
    assertThrows(NullPointerException.class,
        () -> new BulkReindexProgress(generation, target(), null, null, null));

    assertThrows(IllegalArgumentException.class,
        () -> new BulkReindexProgress(generation, target(),
            BulkReindexProgress.Phase.CAPTURING, capture, null));
    assertThrows(IllegalArgumentException.class,
        () -> new BulkReindexProgress(generation, target(),
            BulkReindexProgress.Phase.CAPTURING, null, settled));
    assertThrows(NullPointerException.class,
        () -> new BulkReindexProgress(generation, target(), BulkReindexProgress.Phase.BUILDING, null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new BulkReindexProgress(generation, target(),
            BulkReindexProgress.Phase.BUILDING, capture, settled));
    assertThrows(NullPointerException.class,
        () -> new BulkReindexProgress(generation, target(),
            BulkReindexProgress.Phase.SETTLED, null, settled));
    assertThrows(NullPointerException.class,
        () -> new BulkReindexProgress(generation, target(),
            BulkReindexProgress.Phase.SETTLED, capture, null));
  }

  @Test
  void validatesCaptureSettlementHashesCountsAndGapIdentity() {
    assertThrows(IllegalArgumentException.class,
        () -> new BulkReindexProgress.Capture(HASH_A.toUpperCase(), 0));
    assertThrows(IllegalArgumentException.class, () -> new BulkReindexProgress.Capture("not-a-hash", 0));
    assertThrows(IllegalArgumentException.class, () -> new BulkReindexProgress.Capture(HASH_A, -1));

    assertThrows(IllegalArgumentException.class, () -> settlement(0, HASH_B, List.of(), List.of()));
    assertThrows(IllegalArgumentException.class,
        () -> settlement(1, HASH_B.toUpperCase(), List.of(), List.of()));
    assertThrows(IllegalArgumentException.class, () -> settlement(1, "short", List.of(), List.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new BulkReindexProgress.Settlement(1, HASH_B, -1, 0, List.of(), List.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new BulkReindexProgress.Settlement(1, HASH_B, 0, -1, List.of(), List.of()));

    var duplicate = List.of(gap("same-unit"), gap("same-unit"));
    assertThrows(IllegalArgumentException.class, () -> settlement(1, HASH_B, duplicate, List.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new BulkReindexProgress(generationId(), target(), BulkReindexProgress.Phase.SETTLED,
            new BulkReindexProgress.Capture(HASH_A, 1),
            settlement(1, HASH_B, List.of(gap("unit-1"), gap("unit-2")), List.of())));
  }

  @Test
  void retainsFullGapSetWhileCopyingTheBoundedProcessingHistory() {
    List<OperationOutcomeView.Gap> sourceGaps = new ArrayList<>();
    for (int index = 0; index < 201; index++) sourceGaps.add(gap("unit-" + index));
    List<BulkReindexProgress.ProcessingEvent> sourceHistory = new ArrayList<>();
    for (int index = 0; index < BulkReindexProgress.MAX_PROCESSING_HISTORY; index++) {
      sourceHistory.add(event("SKIPPED", "SKIPPED_POLICY", "POLICY_REFUSAL", "NONE"));
    }

    var progress = new BulkReindexProgress(generationId(), target(), BulkReindexProgress.Phase.SETTLED,
        new BulkReindexProgress.Capture(HASH_A, sourceGaps.size()),
        settlement(2, HASH_C, sourceGaps, sourceHistory));
    sourceGaps.clear();
    sourceHistory.clear();

    assertEquals(201, progress.settlement().gaps().size(), "the full current gap list has no sample cap");
    assertEquals(BulkReindexProgress.MAX_PROCESSING_HISTORY,
        progress.settlement().processingHistory().size());
    assertEquals(0, progress.unitsCompleted());
    assertEquals(201, progress.unitsFailed());
    assertThrows(UnsupportedOperationException.class, () -> progress.settlement().gaps().clear());
    assertThrows(UnsupportedOperationException.class,
        () -> progress.settlement().processingHistory().clear());
    assertThrows(IllegalArgumentException.class,
        () -> new BulkReindexProgress.Settlement(2, HASH_C, 0, 0, List.of(),
            java.util.Collections.nCopies(BulkReindexProgress.MAX_PROCESSING_HISTORY + 1,
                event("INDEXED", "SUCCESS_FULL", "SUCCESS_FULL", "NONE"))));
  }

  @Test
  void validatesProcessingProjectionTokensWithoutRepeatingQueueSemantics() {
    var event = event("INDEXED", "PARSER_FAILED", "SAFE_REASON", "RETRY_WITH_BACKOFF");
    assertEquals("PARSER_FAILED", event.outcomeClass(),
        "the projection validates opaque safe tokens but leaves semantic compatibility to the queue");

    assertThrows(IllegalArgumentException.class,
        () -> event("INDEXED", "SUCCESS_FULL", "SUCCESS_FULL", "NONE",
            HASH_A.toUpperCase(), HASH_B, "unit-1"));
    assertThrows(IllegalArgumentException.class,
        () -> new BulkReindexProgress.ProcessingEvent(HASH_A, "unit-1", "bad-hash", null,
            "INDEXED", "SUCCESS_FULL", "SUCCESS_FULL", "NONE"));
    assertThrows(IllegalArgumentException.class,
        () -> event("INDEXED", "SUCCESS_FULL", "SUCCESS_FULL", "NONE", HASH_A, "bad-hash", "unit-1"));
    assertThrows(IllegalArgumentException.class,
        () -> event("INDEXED", "SUCCESS_FULL", "SUCCESS_FULL", "NONE",
            HASH_A, HASH_B.toUpperCase(), "unit-1"));
    assertThrows(IllegalArgumentException.class,
        () -> event("UNKNOWN", "SUCCESS_FULL", "SUCCESS_FULL", "NONE"));
    assertThrows(IllegalArgumentException.class,
        () -> event(null, "SUCCESS_FULL", "SUCCESS_FULL", "NONE"));
    assertThrows(IllegalArgumentException.class,
        () -> event("INDEXED", "SUCCESS-FULL", "SUCCESS_FULL", "NONE"));
    assertThrows(IllegalArgumentException.class,
        () -> event("INDEXED", "SUCCESS_FULL", "unsafe reason", "NONE"));
    assertThrows(IllegalArgumentException.class,
        () -> event("INDEXED", "SUCCESS_FULL", "SUCCESS_FULL", "RETRY WITH BACKOFF"));
    assertThrows(IllegalArgumentException.class,
        () -> event("INDEXED", "SUCCESS_FULL", "SUCCESS_FULL", "NONE", HASH_A, null, " "));
    assertThrows(IllegalArgumentException.class,
        () -> event("INDEXED", "SUCCESS_FULL", "SUCCESS_FULL", "NONE", HASH_A, null, "r".repeat(129)));
  }

  private static String generationId() {
    // A fixed test clock plus OperationKeys' generator makes an unmistakably synthetic UUIDv7.
    return "g-" + OperationKeys.generate(CLOCK);
  }

  private static IndexTargetSnapshot target() {
    return new IndexTargetSnapshot(sha256(CANONICAL_INPUTS), CANONICAL_INPUTS);
  }

  private static BulkReindexProgress.Settlement settlement(long revision, String hash,
      List<OperationOutcomeView.Gap> gaps, List<BulkReindexProgress.ProcessingEvent> history) {
    return new BulkReindexProgress.Settlement(revision, hash, gaps.size(), history.size(), gaps, history);
  }

  private static OperationOutcomeView.Gap gap(String unitId) {
    return new OperationOutcomeView.Gap(unitId, "SOURCE_GAP");
  }

  private static BulkReindexProgress.ProcessingEvent event(String coverage, String outcomeClass,
      String reasonCode, String retryPolicy) {
    return event(coverage, outcomeClass, reasonCode, retryPolicy, HASH_A, null, "unit-revision-1");
  }

  private static BulkReindexProgress.ProcessingEvent event(String coverage, String outcomeClass,
      String reasonCode, String retryPolicy, String pathHash, String contentHash, String unitRevision) {
    return new BulkReindexProgress.ProcessingEvent(pathHash, unitRevision, HASH_B, contentHash,
        coverage, outcomeClass, reasonCode, retryPolicy);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
