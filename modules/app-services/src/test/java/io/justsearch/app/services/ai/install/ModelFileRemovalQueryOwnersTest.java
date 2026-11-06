/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.DownloadedFiles;
import io.justsearch.app.services.worker.WorkerHost;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Repair round 4 (design D4): the Best match first removal reads the index half's query owners
 * through the composed probe inside its lock, at that moment: while they report a session held
 * (an issued view from before Off, say) it refuses and deletes nothing; once released, the same
 * removal goes ahead. Temporary models folder only.
 */
@Tag("cross-platform")
final class ModelFileRemovalQueryOwnersTest {

  @TempDir java.nio.file.Path tmp;

  @Test
  void aHeldQueryOwnerRefusesTheRemovalAndItsReleaseLetsTheSameRemovalProceed() throws Exception {
    var fixture = new RemovalModelsFixture(tmp.resolve("home"));
    var paths = fixture.writeReranker();
    var settings = new ModelFileRemovalTest.FakeSettings();
    ModelFileRemoval.Eligibility eligible = (id, s) -> Optional.empty();
    var engine = new ModelFileRemoval(fixture.home, fixture.models, () -> fixture.registry,
        () -> null, new ModelFileRemovalFence());
    var use = new AtomicReference<>(WorkerHost.RerankerUse.HELD);
    AtomicInteger asked = new AtomicInteger();
    var composed = ModelFileUsage.probe(() -> {
      asked.incrementAndGet();
      return use.get();
    }, Optional::empty, () -> false, null);
    ModelFileRemoval.UsageProbe probe = composed::apply;
    String digest = engine.view(settings.settings, eligible).parts.stream()
        .filter(p -> p.id.equals("reranker")).findFirst().orElseThrow().previewDigest;

    var refused = assertThrows(ModelFileRemoval.Refusal.class,
        () -> engine.prepare("reranker", digest, settings, eligible, probe));

    assertEquals("Best match first is still loaded - try again in a moment", refused.getMessage());
    assertEquals(1, asked.get(), "asked once, inside the lock");
    assertTrue(Files.exists(paths.get("fp32")) && Files.exists(paths.get("fp16")), "nothing deleted");
    assertTrue(settings.declined.isEmpty(), "nothing declined");

    use.set(WorkerHost.RerankerUse.FREE);
    DownloadedFiles.Removal removal = engine.prepare("reranker", digest, settings, eligible, probe)
        .finish();

    assertEquals(2, asked.get(), "asked again at this removal, never a remembered answer");
    assertEquals("removed", removal.state, removal.message);
    assertFalse(Files.exists(paths.get("fp32")));
    assertFalse(Files.exists(paths.get("fp16")));
    assertEquals(List.of("reranker"), settings.declined);
  }
}
