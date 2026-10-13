/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.PruneRequest;
import io.justsearch.ipc.PruneResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task t-20261013-ef3c41, round 4, P1 — one unreadable subfolder does not stop every genuine
 * deletion under its root.
 *
 * <p>Real conditions: a root with folders {@code a}, {@code b} and {@code c}; {@code b} gets an
 * inherited deny-read ACL for the current user (set and removed with {@code icacls}, so it reaches
 * the files already inside). {@code a/file-0} and {@code c/file-0} are deleted, and so is {@code
 * b/file-0} before {@code b} is denied. The prune removes the two documents whose files are reported
 * not found while their folder can be listed, keeps every document under {@code b} (including the
 * deleted one: a folder that cannot be listed never proves a file absent) and reports itself
 * incomplete. Before this round the first document under {@code b} stopped the whole run and
 * discarded the batch's confirmed deletions, so nothing was removed on any run.
 */
final class PruneDeniedSubfolderTest
    extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  private static final int FILES_PER_FOLDER = 3;

  @TempDir Path tempDir;
  private SqliteJobQueue jobQueue;
  private RunningRuntime runtime;
  private IndexGenerationManager.IndexLayout layout;
  private Path denied;
  private String user;

  @BeforeEach
  void setUp() throws Exception {
    assumeTrue(WINDOWS, "the deny ACL is set through icacls");
    jobQueue = new SqliteJobQueue(tempDir.resolve("jobs.db"));
    jobQueue.open();
    layout = new IndexGenerationManager(tempDir.resolve("indexBase")).initializeOrLoad();
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
        .atPath(layout.activeGenerationPath())
        .withExecutorRegistrations(testLuceneExecutors())
        .open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (denied != null) {
      run("icacls", denied.toString(), "/remove:d", user, "/T", "/C", "/Q");
    }
    if (runtime != null) {
      runtime.close();
    }
    if (jobQueue != null) {
      jobQueue.close();
    }
  }

  @Test
  void aDeniedSubfolderDoesNotStopTheDeletionsBesideIt() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("root"));
    Map<String, String> ids = new LinkedHashMap<>();
    for (String folder : new String[] {"a", "b", "c"}) {
      Path dir = Files.createDirectory(root.resolve(folder));
      for (int i = 0; i < FILES_PER_FOLDER; i++) {
        Path file = Files.writeString(dir.resolve("file-" + i + ".txt"), folder + " " + i);
        ids.put(folder + "/file-" + i, index(file));
      }
    }
    Files.delete(root.resolve("a").resolve("file-0.txt"));
    Files.delete(root.resolve("c").resolve("file-0.txt"));
    Files.delete(root.resolve("b").resolve("file-0.txt"));
    denyReadInherited(root.resolve("b"));

    PruneResponse response = service().pruneMissing(request(root), CallContext.none());

    runtime.commitOps().maybeRefreshBlocking();
    assertNull(content(ids.get("a/file-0")), "a/file-0 was deleted beside the denied folder");
    assertNull(content(ids.get("c/file-0")), "c/file-0 was deleted beside the denied folder");
    assertEquals(2, response.getPrunedCount(), response.getError());
    for (int i = 0; i < FILES_PER_FOLDER; i++) {
      assertNotNull(content(ids.get("b/file-" + i)),
          "b/file-" + i + " is under a folder that cannot be listed: cannot tell, so kept");
    }
    for (int i = 1; i < FILES_PER_FOLDER; i++) {
      assertNotNull(content(ids.get("a/file-" + i)), "a present file is kept");
      assertNotNull(content(ids.get("c/file-" + i)), "a present file is kept");
    }
    assertFalse(response.getError().isEmpty(), "the run still reports itself incomplete");
    assertTrue(response.getError().contains(FILES_PER_FOLDER + " documents"),
        "with the count of documents kept because their presence could not be told: "
            + response.getError());
  }

  /** A fresh service over the same index and queue. */
  private WorkerIngestService service() {
    return new WorkerIngestService(
        jobQueue, mock(IndexingLoop.class), mock(WorkerSignalBus.class),
        IndexingPacing.unthrottled(), layout.basePath(), layout.activeGenerationPath(),
        runtime, runtime, null, 0L);
  }

  private String content(String id) throws Exception {
    return runtime.documentFieldOps().getDocumentField(id, SchemaFields.CONTENT);
  }

  private static PruneRequest request(Path root) {
    return PruneRequest.newBuilder().setPathPrefix(root.toString()).build();
  }

  private String index(Path file) throws Exception {
    String id = PathNormalizer.normalizePath(file.toString());
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, id,
        SchemaFields.DOC_UID, id + "#0",
        SchemaFields.PATH, id,
        SchemaFields.CONTENT, Files.readString(file))));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return id;
  }

  /** Denies read to the current user on {@code dir}, inherited by everything already inside. */
  private void denyReadInherited(Path dir) throws Exception {
    user = whoAmI();
    assertEquals(0, run("icacls", dir.toString(), "/deny", user + ":(OI)(CI)(R)", "/T", "/C"),
        "could not set the deny ACL on " + dir);
    denied = dir;
    assertFalse(Files.isReadable(dir.resolve("file-1.txt")),
        "precondition: the deny reaches the files already inside the folder");
  }

  private static String whoAmI() throws Exception {
    Process process = new ProcessBuilder("whoami").redirectErrorStream(true).start();
    String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "whoami did not finish");
    assertEquals(0, process.exitValue(), "whoami failed: " + out);
    return out;
  }

  private static int run(String... command) throws Exception {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    process.getInputStream().readAllBytes();
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), command[0] + " did not finish");
    return process.exitValue();
  }
}
