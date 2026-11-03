/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.index.IndexGenerationManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class ReleasedOwnershipUpgradeTest {
  @TempDir Path temp;

  @Test void recoveredReleasedBytesStartWithV1AndEveryPreviousPointerFallback() throws Exception { upgrades(false, true); }

  @Test void emptySentinelReleasedBytesStartWithV1AndEveryPreviousPointerFallback() throws Exception { upgrades(true, true); }

  @Test void releasedBytesKeepCurrentPointerCompatibility() throws Exception {
    upgrades(false, false);
    upgrades(true, false);
  }

  private void upgrades(boolean emptySentinel, boolean historical) throws Exception {
    var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String shape : historical ? List.of("v1", "prev-absent", "prev-empty", "prev-torn", "prev-v1") : List.of("v2")) {
      checks.add(() -> upgrade(emptySentinel, shape));
    }
    assertAll(checks);
  }

  private void upgrade(boolean emptySentinel, String shape) throws Exception {
    Path base = temp.resolve((emptySentinel ? "empty-" : "recovered-") + shape);
    Files.createDirectories(base);
    var json = JsonMapper.builder().build();
    ObjectNode state = null;
    try (var source = getClass().getResourceAsStream("/upgrade-030/small.zip")) {
      assertNotNull(source);
      try (var zip = new ZipInputStream(source)) {
        for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
          if (entry.isDirectory()) continue;
          if (entry.getName().equals("data/index/default/state.json")) {
            state = (ObjectNode) json.readTree(zip.readAllBytes());
          } else if (entry.getName().startsWith("data/index/default/indices/")) {
            String relative = entry.getName().substring("data/index/default/".length());
            Path target = base.resolve(relative).normalize();
            assertTrue(target.startsWith(base));
            Files.createDirectories(target.getParent());
            Files.copy(zip, target);
          }
        }
      }
    }
    assertNotNull(state);
    String id = state.path("active_generation").asString();
    Path active = base.resolve("indices").resolve(id);
    if (emptySentinel) Files.write(active.resolve(".justsearch-generation.sentinel"), new byte[0]);
    else {
      Path backup = active.resolveSibling(id + ".bak-20261022-000000");
      Files.createDirectory(backup);
      for (String metadata : List.of(".justsearch-generation.sentinel", ".justsearch-index-generation.json")) {
        Files.move(active.resolve(metadata), backup.resolve(metadata));
      }
    }
    state.put("format_version", shape.equals("v1") || shape.equals("prev-v1") ? 1 : 2);
    Files.writeString(base.resolve(shape.startsWith("prev") ? "state.json.prev" : "state.json"), json.writeValueAsString(state));
    if (shape.equals("prev-empty")) Files.writeString(base.resolve("state.json"), "");
    if (shape.equals("prev-torn")) Files.writeString(base.resolve("state.json"), "{torn");
    var manager = new IndexGenerationManager(base);
    if (!emptySentinel) assertDoesNotThrow(() -> { manager.reconcileActiveRecoveryOwnership(); }, shape);
    assertEquals(id, assertDoesNotThrow(() -> manager.initializeForBoot(
        new IndexGenerationManager.BootOwnership.Native(), null)).layout().activeGenerationId(), shape);
    try (var directory = FSDirectory.open(active); var reader = DirectoryReader.open(directory)) {
      assertEquals(38, new IndexSearcher(reader).count(new MatchAllDocsQuery()), shape);
      for (int doc = 0; doc < reader.maxDoc(); doc++) assertNotNull(reader.storedFields().document(doc));
    }
  }
}
