/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #758 — an index whose stored fingerprint cannot be parsed opens
 * without an automatic rebuild: the open-time parity diff files no difference for a digest it
 * cannot read. A readable but different digest still does (schema mismatch still migrates).
 */
final class UnreadableFingerprintParityTest {

  @Test
  void anUnparseableStoredFingerprintIsNotARebuildingDifference() {
    Map<String, Object> expected = new SsotCommitMetadataSource().build();
    Map<String, String> stored = storedCopyOf(expected);
    stored.put(IndexFingerprint.COMMIT_META_KEY, "{truncated-by-a-crash");

    List<ParityDiagnostics.Diff> diffs = ParityDiagnostics.diff(stored, expected, 1_234L);

    assertEquals(List.of(), diffs,
        "an unreadable fingerprint is an unanswered question; rebuilding 1234 documents on it"
            + " discards the index for nothing");
  }

  @Test
  void aReadableDifferentFingerprintStillRequiresMigration() {
    // P2 (must not break).
    Map<String, Object> expected = new SsotCommitMetadataSource().build();
    Map<String, String> stored = storedCopyOf(expected);
    stored.put(IndexFingerprint.COMMIT_META_KEY, "f".repeat(64));

    List<ParityDiagnostics.Diff> diffs = ParityDiagnostics.diff(stored, expected, 1_234L);

    assertTrue(diffs.stream().anyMatch(d -> IndexFingerprint.COMMIT_META_KEY.equals(d.key())),
        "a different, readable shape is a real mismatch: " + diffs);
  }

  private static Map<String, String> storedCopyOf(Map<String, Object> expected) {
    Map<String, String> stored = new HashMap<>();
    expected.forEach((k, v) -> stored.put(k, String.valueOf(v)));
    return stored;
  }
}
