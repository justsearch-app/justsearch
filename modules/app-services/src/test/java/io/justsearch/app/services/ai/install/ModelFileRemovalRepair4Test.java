/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.DownloadedFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Repair round 4 of "Remove files" (design D4), on real files in a temporary models folder only: a
 * file that appears during the removal at one of the part's canonical places that was empty when
 * the removal was prepared is kept, reported, and keeps the removal from being reported as
 * complete, when it finishes and after a crash.
 */
@Tag("cross-platform")
final class ModelFileRemovalRepair4Test {
  /** ModelFileRemoval.KEPT_NEW_FILE, as the page shows it. */
  private static final String KEPT_NEW_FILE = "kept - a new file appeared here";

  @TempDir Path tmp;

  private final ModelFileRemovalTest.FakeSettings settings = new ModelFileRemovalTest.FakeSettings();
  private static final ModelFileRemoval.Eligibility ELIGIBLE = (id, s) -> Optional.empty();
  private static final ModelFileRemoval.UsageProbe IDLE = id -> Optional.empty();

  private RemovalModelsFixture fixture;

  private ModelFileRemoval engine() {
    return new ModelFileRemoval(fixture.home, fixture.models, () -> fixture.registry, () -> null,
        new ModelFileRemovalFence());
  }

  private DownloadedFiles.Part part(ModelFileRemoval engine, String id) {
    return engine.view(settings.settings, ELIGIBLE).parts.stream()
        .filter(p -> p.id.equals(id)).findFirst().orElseThrow();
  }

  private static List<String> reasons(DownloadedFiles.Removal removal, String path) {
    return removal.kept.stream().filter(f -> f.path.equals(path)).map(f -> f.reason).toList();
  }

  private static List<String> deleted(DownloadedFiles.Removal removal) {
    return removal.deleted.stream().map(f -> f.path).toList();
  }

  private static void write(Path file, byte[] data) {
    try {
      Files.createDirectories(file.getParent());
      Files.write(file, data);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void aProjectorThatAppearsWhileTheAnswerModelIsHashedIsKeptAndTheRemovalIsNotComplete()
      throws Exception {
    fixture = new RemovalModelsFixture(tmp.resolve("home"));
    Files.write(fixture.models.resolve("answer.gguf"), RemovalModelsFixture.CHAT);
    Path projector = fixture.models.resolve("mmproj-F16.gguf");
    ModelFileRemoval engine = engine();
    DownloadedFiles.Part preview = part(engine, "chat");
    assertEquals(List.of("answer.gguf"), preview.delete.stream().map(f -> f.path).toList(),
        "precondition: the projector was absent when the removal was prepared");
    // Captured, not yet hashed: the projector is written at its own, previously empty place.
    engine.setCheckpointHookForTest(name -> {
      if (name.equals("captured:answer.gguf")) write(projector, RemovalModelsFixture.PROJECTOR);
    });

    DownloadedFiles.Removal removal =
        engine.prepare("chat", preview.previewDigest, settings, ELIGIBLE, IDLE).finish();

    assertEquals(List.of("answer.gguf"), deleted(removal), "precondition: the verified model went");
    assertArrayEquals(RemovalModelsFixture.PROJECTOR, Files.readAllBytes(projector),
        "the new file is never deleted");
    assertEquals(List.of(KEPT_NEW_FILE), reasons(removal, "mmproj-F16.gguf"));
    assertEquals(Long.valueOf(RemovalModelsFixture.PROJECTOR.length), removal.kept.stream()
        .filter(f -> f.path.equals("mmproj-F16.gguf")).findFirst().orElseThrow().sizeBytes,
        "measured");
    assertEquals("partly-removed", removal.state, "not removed while a file sits there: " + removal.message);

    DownloadedFiles.Removal shown = engine.view(settings.settings, ELIGIBLE).removal;
    assertEquals("partly-removed", shown.state, "the page's report says the same");
    assertEquals(List.of(KEPT_NEW_FILE), reasons(shown, "mmproj-F16.gguf"));
  }

  @Test
  void aNewlyAppearingBestMatchFirstVariantIsKeptAndTheRemovalIsNotComplete() throws Exception {
    fixture = new RemovalModelsFixture(tmp.resolve("home"));
    Path fp32 = fixture.rerankerDir(0);
    Files.createDirectories(fp32);
    Files.write(fp32.resolve("model.onnx"), RemovalModelsFixture.RERANK_FP32);
    Files.write(fp32.resolve("tokenizer.json"), RemovalModelsFixture.TOKENIZER);
    Files.write(fp32.resolve("config.json"), RemovalModelsFixture.CONFIG);
    Path fp16 = fixture.rerankerDir(1).resolve("model_fp16.onnx");
    String fp16Rel = fixture.rerankerRel(1, "model_fp16.onnx");
    String fp32Rel = fixture.rerankerRel(0, "model.onnx");
    ModelFileRemoval engine = engine();
    DownloadedFiles.Part preview = part(engine, "reranker");
    assertEquals(3, preview.delete.size(), "precondition: only the FP32 variant is present");
    engine.setCheckpointHookForTest(name -> {
      if (name.equals("captured:" + fp32Rel)) write(fp16, RemovalModelsFixture.RERANK_FP16);
    });

    DownloadedFiles.Removal removal =
        engine.prepare("reranker", preview.previewDigest, settings, ELIGIBLE, IDLE).finish();

    assertEquals(3, removal.deleted.size(), "precondition: the verified FP32 files went: "
        + deleted(removal));
    assertArrayEquals(RemovalModelsFixture.RERANK_FP16, Files.readAllBytes(fp16),
        "the new variant is never deleted");
    assertEquals(List.of(KEPT_NEW_FILE), reasons(removal, fp16Rel));
    assertEquals("partly-removed", removal.state, removal.message);
  }

  @Test
  void aFileAtAPreviouslyEmptyPlaceIsReportedByRecoveryAfterACrash() throws Exception {
    fixture = new RemovalModelsFixture(tmp.resolve("home"));
    Files.write(fixture.models.resolve("answer.gguf"), RemovalModelsFixture.CHAT);
    Path projector = fixture.models.resolve("mmproj-F16.gguf");
    ModelFileRemoval engine = engine();
    engine.setCheckpointHookForTest(name -> {
      if (!name.equals("captured:answer.gguf")) return;
      write(projector, RemovalModelsFixture.PROJECTOR);
      throw new ModelFileRemovalTest.SimulatedDeath();
    });
    ModelFileRemoval.Run run =
        engine.prepare("chat", part(engine, "chat").previewDigest, settings, ELIGIBLE, IDLE);
    assertThrows(ModelFileRemovalTest.SimulatedDeath.class, run::finish);

    // A new process over the same folders.
    DownloadedFiles.Removal report = engine().view(settings.settings, ELIGIBLE).removal;

    assertEquals("interrupted", report.state);
    assertEquals(List.of(KEPT_NEW_FILE), reasons(report, "mmproj-F16.gguf"),
        "recovery looks at every place of the part: " + report.kept.stream().map(f -> f.path).toList());
    assertArrayEquals(RemovalModelsFixture.PROJECTOR, Files.readAllBytes(projector),
        "recovery never deletes");
    assertTrue(Files.exists(fixture.models.resolve("answer.gguf")), "the captured model was put back");
    assertFalse(fixture.stagedFiles().stream().findAny().isPresent(), "nothing left captured");
  }
}
