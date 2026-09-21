/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.app.api.status.MigrationSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RecordedBulkPlanTest {
  private static final Path ROOT = Path.of("recorded-bulk-plan").toAbsolutePath().normalize();
  private static final String INPUTS = "{\"dimension\":768}";

  @Test
  void replayRoundTripsTheFrozenProfileScopeAndPhysicalTarget() {
    RecordedBulkPlan plan = plan(RecordedBulkPlan.Profile.USER_BULK,
        MigrationSource.USER_REQUESTED_BULK_REINDEX.wire(), scope(true, false));

    assertEquals(plan, RecordedBulkPlan.fromReplayPayload(plan.toReplayPayload()));
    assertEquals(plan.planHash(), RecordedBulkPlan.fromReplayPayload(plan.toReplayPayload()).planHash());
  }

  @Test
  void replayRejectsUnknownDuplicateAndTrailingFields() {
    String valid = plan(RecordedBulkPlan.Profile.USER_BULK,
        MigrationSource.USER_REQUESTED_BULK_REINDEX.wire(), scope(true, false)).toReplayPayload();

    assertThrows(IllegalArgumentException.class,
        () -> RecordedBulkPlan.fromReplayPayload(valid.replace(
            "\"profile\":\"USER_BULK\"", "\"profile\":\"USER_BULK\",\"unexpected\":true")));
    assertThrows(IllegalArgumentException.class,
        () -> RecordedBulkPlan.fromReplayPayload(valid.replace(
            "\"profile\":\"USER_BULK\"", "\"profile\":\"USER_BULK\",\"profile\":\"USER_BULK\"")));
    assertThrows(IllegalArgumentException.class,
        () -> RecordedBulkPlan.fromReplayPayload(valid + " {}"));
  }

  @Test
  void replayRejectsTargetFingerprintThatNoLongerMatchesOpaqueInputs() {
    RecordedBulkPlan plan = plan(RecordedBulkPlan.Profile.USER_BULK,
        MigrationSource.USER_REQUESTED_BULK_REINDEX.wire(), scope(true, false));
    String tampered = plan.toReplayPayload().replace(plan.target().fingerprint(), "0".repeat(64));

    assertThrows(IllegalArgumentException.class, () -> RecordedBulkPlan.fromReplayPayload(tampered));
  }

  @Test
  void bulkPlansRequireForcedWatchedRootTraversal() {
    assertThrows(IllegalArgumentException.class,
        () -> plan(RecordedBulkPlan.Profile.USER_BULK,
            MigrationSource.USER_REQUESTED_BULK_REINDEX.wire(), scope(false, false)));
    assertThrows(IllegalArgumentException.class,
        () -> plan(RecordedBulkPlan.Profile.USER_BULK,
            MigrationSource.USER_REQUESTED_BULK_REINDEX.wire(), scope(true, true)));
  }

  @Test
  void publicArgumentsKeepLegacyBulkLabelsAndProfileSpecificSourceDefaults() {
    assertEquals(MigrationSource.USER_REQUESTED_BULK_REINDEX.wire(),
        RecordedBulkPlan.sourceForArguments(RecordedBulkPlan.Profile.USER_BULK,
            "{\"corpusIds\":[\"docs\"]}"));
    assertEquals(MigrationSource.USER_REQUESTED_REBUILD.wire(),
        RecordedBulkPlan.sourceForArguments(RecordedBulkPlan.Profile.RECOVERY_REBUILD, "{}"));
    assertThrows(IllegalArgumentException.class,
        () -> RecordedBulkPlan.sourceForArguments(RecordedBulkPlan.Profile.USER_BULK, "{}"));
    assertThrows(IllegalArgumentException.class,
        () -> RecordedBulkPlan.sourceForArguments(RecordedBulkPlan.Profile.RECOVERY_REBUILD,
            "{\"corpusIds\":[]}"));
    assertThrows(IllegalArgumentException.class,
        () -> RecordedBulkPlan.sourceForArguments(RecordedBulkPlan.Profile.USER_BULK,
            "{\"corpusIds\":[\"docs\"],\"source\":\" user-requested-bulk-reindex \"}"));
  }

  private static RecordedBulkPlan plan(RecordedBulkPlan.Profile profile, String source,
      RecordedRootPlan scope) {
    return new RecordedBulkPlan(profile, source, scope,
        new IndexTargetSnapshot(sha256(INPUTS), INPUTS));
  }

  private static RecordedRootPlan scope(boolean force, boolean singleFile) {
    return new RecordedRootPlan("serving-generation", List.of(
        new RecordedRootPlan.Root(ROOT, "documents", force, singleFile, List.of("*.tmp"), List.of())));
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
