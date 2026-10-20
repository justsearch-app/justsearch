/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GenerationPointerPreviousRepairTest {
  private static final String KEY = "01994180-0000-7000-8000-000000000121";
  private static final String FINGERPRINT = "b".repeat(64);
  @TempDir Path base;

  @Test
  void exactUnsettledReceiptsAndGenerationMetadataRepairMissingPointer() throws Exception {
    var owner = prepare();
    byte[] previous = Files.readAllBytes(base.resolve("state.json.prev"));
    var boot = assertDoesNotThrow(() -> new IndexGenerationManager(base)
        .initializeForBoot(owner, FINGERPRINT));
    assertEquals(IndexGenerationManager.BootDisposition.BUILDING, boot.disposition());
    assertEquals(owner.sourceGeneration(), boot.layout().activeGenerationId());
    assertArrayEquals(previous, Files.readAllBytes(base.resolve("state.json.prev")));
    assertEquals(boot.layout().state(), new IndexGenerationManager(base).readStateBestEffort());
  }

  @ParameterizedTest
  @ValueSource(strings = {"source", "fingerprint", "orphan", "manifest", "sentinel", "receipts"})
  void conflictingOrMissingEvidenceRefusesWithoutRewritingPrevious(String defect) throws Exception {
    var owner = prepare();
    Path target = base.resolve("indices/g-" + KEY);
    if ("orphan".equals(defect)) Files.createDirectory(base.resolve("indices/unrelated"));
    if ("manifest".equals(defect)) Files.delete(target.resolve(".justsearch-index-generation.json"));
    if ("sentinel".equals(defect)) Files.delete(target.resolve(".justsearch-generation.sentinel"));
    var checked = new IndexGenerationManager.BootOwnership.Recorded(KEY,
        "source".equals(defect) ? "unrelated-source" : owner.sourceGeneration(),
        "recorded-repair", "fingerprint".equals(defect) ? "c".repeat(64) : FINGERPRINT,
        true, true, null, !"receipts".equals(defect));
    byte[] previous = Files.readAllBytes(base.resolve("state.json.prev"));
    assertThrows(IOException.class, () -> new IndexGenerationManager(base)
        .initializeForBoot(checked, FINGERPRINT));
    assertFalse(Files.exists(base.resolve("state.json")));
    assertArrayEquals(previous, Files.readAllBytes(base.resolve("state.json.prev")));
  }

  @Test
  void presentMalformedPointerCannotBeReplacedByHistoricalEvidence() throws Exception {
    var owner = prepare();
    Files.writeString(base.resolve("state.json"), "{torn after a later decision");
    assertThrows(IOException.class, () -> new IndexGenerationManager(base)
        .initializeForBoot(owner, FINGERPRINT));
    assertEquals("{torn after a later decision", Files.readString(base.resolve("state.json")));
  }

  private IndexGenerationManager.BootOwnership.Recorded prepare() throws Exception {
    var manager = new IndexGenerationManager(base);
    var initial = manager.initializeOrLoad();
    manager.startRecordedMigration(KEY, "recorded-repair", FINGERPRINT);
    Files.copy(base.resolve("state.json"), base.resolve("state.json.prev"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    Files.delete(base.resolve("state.json"));
    return new IndexGenerationManager.BootOwnership.Recorded(KEY, initial.activeGenerationId(),
        "recorded-repair", FINGERPRINT, true, true, null, true);
  }
}
