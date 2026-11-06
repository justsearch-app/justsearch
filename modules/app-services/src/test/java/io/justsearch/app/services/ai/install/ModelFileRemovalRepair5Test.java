/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.app.api.DownloadedFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Repair round 5 of "Remove files" (design D4), on real files in a temporary models folder only: a
 * place of the part whose presence cannot be determined (here a retained Best match first variant
 * folder the Engine's account cannot search) is not absent. It is kept and reported "could not be
 * checked", never reported deleted, and the removal is not reported complete: when the files are
 * listed, when a folder becomes inaccessible during the removal, and in recovery after a crash.
 */
@Tag("cross-platform")
final class ModelFileRemovalRepair5Test {
  /** ModelFileRemoval.KEPT_UNCHECKED, as the page shows it. */
  private static final String KEPT_UNCHECKED = "kept - could not be checked";

  @TempDir Path tmp;

  private final ModelFileRemovalTest.FakeSettings settings = new ModelFileRemovalTest.FakeSettings();
  private static final ModelFileRemoval.Eligibility ELIGIBLE = (id, s) -> Optional.empty();
  private static final ModelFileRemoval.UsageProbe IDLE = id -> Optional.empty();

  private RemovalModelsFixture fixture;
  private Path locked;

  @AfterEach
  void unlock() throws IOException {
    if (locked != null) Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
  }

  private ModelFileRemoval engine() {
    return new ModelFileRemoval(fixture.home, fixture.models, () -> fixture.registry, () -> null,
        new ModelFileRemovalFence());
  }

  private DownloadedFiles.Part part(ModelFileRemoval engine) {
    return engine.view(settings.settings, ELIGIBLE).parts.stream()
        .filter(p -> p.id.equals("reranker")).findFirst().orElseThrow();
  }

  private static List<String> reasons(DownloadedFiles.Removal removal, String path) {
    return removal.kept.stream().filter(f -> f.path.equals(path)).map(f -> f.reason).toList();
  }

  /** Makes the FP16 variant folder unsearchable, so its files' presence cannot be determined. */
  private void lockFp16Folder() {
    locked = fixture.rerankerDir(1);
    try {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-------"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void requireUndetermined() {
    Path model = fixture.rerankerDir(1).resolve("model_fp16.onnx");
    assumeTrue(!Files.exists(model) && !Files.notExists(model),
        "this account can still search a folder without search permission (root?)");
  }

  private void setUp() throws Exception {
    fixture = new RemovalModelsFixture(tmp.resolve("home"));
    assumeTrue(RemovalLinks.posix(fixture.models), "the folder is made inaccessible through POSIX permissions");
    fixture.writeReranker();
  }

  @Test
  void anInaccessibleRetainedVariantIsReportedAndTheRemovalIsOnlyPartly() throws Exception {
    setUp();
    String fp16Rel = fixture.rerankerRel(1, "model_fp16.onnx");
    lockFp16Folder();
    requireUndetermined();
    ModelFileRemoval engine = engine();
    DownloadedFiles.Part preview = part(engine);
    assertEquals(List.of(KEPT_UNCHECKED), preview.kept.stream()
        .filter(f -> f.path.equals(fp16Rel)).map(f -> f.reason).toList(),
        "the confirmation lists the place that could not be checked: " + preview.kept);

    DownloadedFiles.Removal removal =
        engine.prepare("reranker", preview.previewDigest, settings, ELIGIBLE, IDLE).finish();

    assertEquals(3, removal.deleted.size(), "the accessible variant's verified files went: "
        + removal.deleted.stream().map(f -> f.path).toList());
    assertEquals(List.of(KEPT_UNCHECKED), reasons(removal, fp16Rel));
    assertEquals("partly-removed", removal.state, "never removed while a place is unresolved: "
        + removal.message);
    assertEquals("partly-removed", engine.view(settings.settings, ELIGIBLE).removal.state,
        "the page's report says the same");
    unlock();
    locked = null;
    assertArrayEquals(RemovalModelsFixture.RERANK_FP16,
        Files.readAllBytes(fixture.rerankerDir(1).resolve("model_fp16.onnx")), "never deleted");
  }

  @Test
  void aVariantFolderThatBecomesInaccessibleDuringTheRemovalIsReportedAndKept() throws Exception {
    setUp();
    String fp16Rel = fixture.rerankerRel(1, "model_fp16.onnx");
    String fp32Rel = fixture.rerankerRel(0, "model.onnx");
    ModelFileRemoval engine = engine();
    DownloadedFiles.Part preview = part(engine);
    assertEquals(6, preview.delete.size(), "precondition: both variants are listed for deletion");
    engine.setCheckpointHookForTest(name -> {
      if (name.equals("captured:" + fp32Rel)) lockFp16Folder();
    });

    DownloadedFiles.Removal removal =
        engine.prepare("reranker", preview.previewDigest, settings, ELIGIBLE, IDLE).finish();

    requireUndetermined();
    assertEquals(3, removal.deleted.size(), "the accessible variant's verified files went");
    assertEquals(List.of(KEPT_UNCHECKED), reasons(removal, fp16Rel));
    assertEquals("partly-removed", removal.state, removal.message);
  }

  @Test
  void recoveryReportsAPlaceThatCouldNotBeCheckedAndNeverCallsItDeleted() throws Exception {
    setUp();
    String fp16Rel = fixture.rerankerRel(1, "model_fp16.onnx");
    String fp32Rel = fixture.rerankerRel(0, "model.onnx");
    ModelFileRemoval engine = engine();
    engine.setCheckpointHookForTest(name -> {
      if (!name.equals("captured:" + fp32Rel)) return;
      lockFp16Folder();
      throw new ModelFileRemovalTest.SimulatedDeath();
    });
    ModelFileRemoval.Run run =
        engine.prepare("reranker", part(engine).previewDigest, settings, ELIGIBLE, IDLE);
    assertThrows(ModelFileRemovalTest.SimulatedDeath.class, run::finish);
    requireUndetermined();

    // A new process over the same folders.
    DownloadedFiles.Removal report = engine().view(settings.settings, ELIGIBLE).removal;

    assertEquals("interrupted", report.state);
    assertEquals(List.of(KEPT_UNCHECKED), reasons(report, fp16Rel),
        "recovery reports the place it could not check: " + report.kept.stream()
            .map(f -> f.path + " " + f.reason).toList());
    assertFalse(report.deleted.stream().anyMatch(f -> f.path.equals(fp16Rel)), "never reported deleted");
    assertTrue(Files.exists(fixture.models.resolve(fp32Rel)), "the captured model was put back");
    unlock();
    locked = null;
    assertArrayEquals(RemovalModelsFixture.RERANK_FP16,
        Files.readAllBytes(fixture.rerankerDir(1).resolve("model_fp16.onnx")), "recovery never deletes");
  }
}
