/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.DownloadedFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The runtime refresh at the end of a removal: it runs once the files are gone, while the removal
 * still holds the lock and reports running, and it can never change what the removal reports.
 */
@Tag("cross-platform")
final class ModelFileRemovalRefreshTest {
  private static final ModelFileRemoval.Eligibility ELIGIBLE = (id, s) -> Optional.empty();
  private static final ModelFileRemoval.UsageProbe IDLE = id -> Optional.empty();
  private static final Pattern RUNNING = Pattern.compile("\"state\"\\s*:\\s*\"running\"");

  @TempDir Path tmp;
  private final ModelFileRemovalFence fence = new ModelFileRemovalFence();
  private final ModelFileRemovalTest.FakeSettings saved = new ModelFileRemovalTest.FakeSettings();

  /** The settings owner with its end-of-removal refresh replaced by {@code refresh}. */
  private ModelFileRemoval.Settings refreshing(java.util.function.Consumer<String> refresh) {
    return new ModelFileRemoval.Settings() {
      @Override public ModelFileRemoval.UiSettingsStoreView inspect() {
        return saved.inspect();
      }

      @Override public void decline(ModelFileRemoval.UiSettingsStoreView read, String packageId) {
        saved.decline(read, packageId);
      }

      @Override public void filesChanged(String packageId) {
        refresh.accept(packageId);
      }
    };
  }

  private DownloadedFiles.Removal removeAnswers(RemovalModelsFixture files,
      ModelFileRemoval.Settings settings) {
    var engine = new ModelFileRemoval(files.home, files.models, () -> files.registry, () -> null, fence);
    String digest = engine.view(saved.settings, ELIGIBLE).parts.stream()
        .filter(p -> p.id.equals("chat")).findFirst().orElseThrow().previewDigest;
    return engine.prepare("chat", digest, settings, ELIGIBLE, IDLE).finish();
  }

  @Test
  void theRefreshRunsOnceTheFilesAreGoneWhileTheRemovalStillHoldsTheLockAndReportsRunning()
      throws Exception {
    var files = new RemovalModelsFixture(tmp.resolve("home"));
    files.writeChat();
    Path model = files.models.resolve("answer.gguf");
    Path journal = files.home.resolve(ModelFileRemoval.JOURNAL_FILENAME);
    List<String> seen = new ArrayList<>();

    var removal = removeAnswers(files, refreshing(id -> {
      seen.add(id);
      assertFalse(Files.exists(model), "the refresh must see the model gone");
      assertEquals(Optional.of("chat"), fence.removingPart(), "the lock is still held");
      try {
        assertTrue(RUNNING.matcher(Files.readString(journal)).find(),
            "the page keeps waiting until the refresh has finished");
      } catch (java.io.IOException unreadable) {
        throw new AssertionError(unreadable);
      }
    }));

    assertEquals(List.of("chat"), seen, "exactly one refresh");
    assertEquals("removed", removal.state, removal.message);
    assertEquals(Optional.empty(), fence.removingPart(), "the lock is released afterwards");
  }

  @Test
  void aRefreshThatFailsLeavesTheRemovalReportedAsItHappened() throws Exception {
    var files = new RemovalModelsFixture(tmp.resolve("home"));
    files.writeChat();

    var removal = removeAnswers(files, refreshing(id -> {
      throw new IllegalStateException("settings are busy");
    }));

    assertEquals("removed", removal.state, removal.message);
    assertEquals(2, removal.deleted.size(), "both files are reported deleted");
    assertFalse(Files.exists(files.models.resolve("answer.gguf")));
    assertEquals(List.of("chat"), saved.declined, "the decline stands");
  }
}
