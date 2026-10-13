/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task t-20261013-ef3c41, scenario S2 — a prune removes a document only when its file is not found
 * while the same root is present, re-checked during the prune; when the root stops answering, part
 * way or from the start, nothing more is removed.
 *
 * <p>Real file-system conditions only: the root is renamed away (before or during the prune) or
 * the drive letter it lives on is unmapped (SUBST). The mapping is removed before the test returns.
 */
final class PruneUncertainAbsenceTest extends LuceneExecutorTestBase {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

  private static final CommitMetadataSource METADATA =
      () -> Map.of(
          "index_fingerprint", "prune-uncertain-1.0.0",
          "schema_fp", "test-fingerprint",
          "boosts_fp", "test-boosts",
          "dag_hash", "test-dag-hash",
          "pipeline_budget_profile", "test-profile",
          "field_catalog_hash", "test-catalog-hash",
          "synonyms_hash", "test-synonyms-hash");

  @TempDir Path tempDir;
  private RunningRuntime runtime;

  @BeforeEach
  void setUp() throws Exception {
    FieldCatalogDef catalog = new FieldCatalogDef("prune-uncertain-v1", List.of(
        new FieldDef("doc_id", "keyword", true, true, List.of("id", "sort"), null, null, false),
        new FieldDef("doc_uid", "keyword", false, true, List.of("sort", "tiebreak"), null, null,
            false),
        new FieldDef("content", "text", true, false, List.of("highlight"), null, "icu", false),
        new FieldDef("path", "keyword", true, true, List.of("filter", "sort"), null, null, false),
        new FieldDef("is_chunk", "keyword", true, true, List.of("filter"), null, null, false),
        new FieldDef("parent_doc_id", "keyword", true, true, List.of("filter"), null, null,
            false)));
    runtime =
        IndexSchema.fromCatalog(catalog, METADATA, metadata -> {})
            .atPath(Files.createDirectory(tempDir.resolve("index")))
            .withExecutorRegistrations(testLuceneExecutors())
            .open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runtime != null) {
      runtime.close();
    }
  }

  @Test
  void aRootRenamedAwayBeforeThePruneLosesNoDocuments() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("photos"));
    List<String> ids = indexFiles(root, 3);
    Files.move(root, tempDir.resolve("photos-renamed"));

    runtime.pruneOps().pruneByPathPrefix(root.toString(), () -> false, 100);

    assertEquals(3, surviving(ids),
        "a root that is gone vouches for no deletion; every document must stay");
  }

  @Test
  void aRootRenamedAwayMidPruneStopsTheRemovals() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("projects"));
    List<String> ids = indexFiles(root, 8);
    AtomicInteger docsVisited = new AtomicInteger();
    Path renamed = tempDir.resolve("projects-renamed");

    // The abort checker runs once per document; the root is renamed after the second, which is
    // the moment a drive is unplugged or a share drops part-way through a prune.
    runtime.pruneOps().pruneByPathPrefix(
        root.toString(),
        () -> {
          if (docsVisited.incrementAndGet() == 3) {
            try {
              Files.move(root, renamed);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          }
          return false;
        },
        2);

    assertTrue(Files.isDirectory(renamed), "precondition: the root really moved mid-prune");
    assertEquals(8, surviving(ids),
        "every file still exists (under the renamed root); none may be pruned");
  }

  @Test
  void anUnmappedDriveLetterLosesNoDocuments() throws Exception {
    assumeTrue(WINDOWS, "SUBST drive letters exist on Windows only");
    Path target = Files.createDirectory(tempDir.resolve("usb-stick"));
    try (SubstDrive drive = SubstDrive.map(target)) {
      Path root = drive.root().resolve("music");
      Files.createDirectory(root);
      List<String> ids = indexFiles(root, 3);
      drive.unmap(); // the stick is pulled

      runtime.pruneOps().pruneByPathPrefix(root.toString(), () -> false, 100);

      assertEquals(3, surviving(ids), "an unplugged drive is not a mass deletion");
    }
  }

  @Test
  void aGenuinelyDeletedFileIsStillPrunedInOneRun() throws Exception {
    // P1 (must not break): within one cycle once the root is reachable.
    Path root = Files.createDirectory(tempDir.resolve("notes"));
    List<String> ids = indexFiles(root, 3);
    Files.delete(Path.of(ids.get(1)));

    int pruned = runtime.pruneOps().pruneByPathPrefix(root.toString(), () -> false, 100);

    assertEquals(1, pruned);
    assertEquals(2, surviving(ids));
  }

  @Test
  void aStoredPathThisSystemCannotParseDoesNotHaltGenuineDeletions() throws Exception {
    // P1: a genuinely deleted file goes within one run. Before the change, one stored path this
    // system rejects (here a NUL character) threw out of the prune and halted every deletion
    // under the root, run after run; that document is now kept and the rest are still judged.
    Path root = Files.createDirectory(tempDir.resolve("letters"));
    List<String> ids = indexFiles(root, 3);
    String unparseable =
        normalize(root) + File.separator + "bad" + (char) 0 + "name.txt";
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, unparseable);
    fields.put(SchemaFields.DOC_UID, unparseable + "#1");
    fields.put("content", "unparseable");
    fields.put("path", unparseable);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    Files.delete(Path.of(ids.get(1)));

    int pruned = assertDoesNotThrow(
        () -> runtime.pruneOps().pruneByPathPrefix(root.toString(), () -> false, 100),
        "one unparseable stored path must not halt the prune");

    assertEquals(1, pruned);
    assertEquals(2, surviving(ids));
    assertEquals(1, surviving(List.of(unparseable)), "what cannot be judged is kept");
  }

  private List<String> indexFiles(Path root, int count) throws IOException {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Path file = Files.writeString(root.resolve("file-" + i + ".txt"), "content " + i);
      String id = normalize(file);
      Map<String, Object> fields = new HashMap<>();
      fields.put(SchemaFields.DOC_ID, id);
      fields.put(SchemaFields.DOC_UID, id + "#1");
      fields.put("content", "content " + i);
      fields.put("path", id);
      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
      ids.add(id);
    }
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return ids;
  }

  private long surviving(List<String> ids) {
    runtime.commitOps().maybeRefreshBlocking();
    return ids.stream()
        .filter(id -> runtime.documentFieldOps().getDocumentField(id, "content") != null)
        .count();
  }

  private static String normalize(Path path) {
    String normalized = path.toAbsolutePath().toString();
    return WINDOWS ? normalized.toLowerCase(Locale.ROOT) : normalized;
  }

  /** A SUBST drive letter mapped to a folder this test created; unmapped on close. */
  private static final class SubstDrive implements AutoCloseable {
    private final String letter;
    private boolean mapped = true;

    private SubstDrive(String letter) {
      this.letter = letter;
    }

    static SubstDrive map(Path target) throws Exception {
      Set<Character> used =
          java.util.Arrays.stream(File.listRoots())
              .map(r -> Character.toUpperCase(r.getPath().charAt(0)))
              .collect(Collectors.toSet());
      for (char c = 'Y'; c >= 'M'; c--) {
        if (used.contains(c)) continue;
        String letter = c + ":";
        if (run("subst", letter, target.toString()) == 0) {
          return new SubstDrive(letter);
        }
      }
      assumeTrue(false, "no free drive letter for SUBST");
      throw new IllegalStateException("unreachable");
    }

    Path root() {
      return Path.of(letter + "\\");
    }

    void unmap() throws Exception {
      if (mapped) {
        assertEquals(0, run("subst", letter, "/d"), "could not remove SUBST " + letter);
        mapped = false;
      }
    }

    @Override
    public void close() throws Exception {
      unmap();
    }

    private static int run(String... command) throws Exception {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      process.getInputStream().readAllBytes();
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "subst did not finish");
      return process.exitValue();
    }
  }
}
