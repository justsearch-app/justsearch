/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.ScanMode;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResult;
import io.justsearch.ipc.SyncDirectoryRequest;
import io.justsearch.ipc.SyncDirectoryResponse;
import io.justsearch.ipc.WatchRootRequest;
import io.justsearch.ipc.WatchRootResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task t-20261013-ef3c41, round 9 — the Worker's volume record ({@code root-volumes.properties})
 * lost or corrupt after it was written. A lost or unreadable record never causes a deletion: at
 * the next boot every declared root awaits its one-time seeding on evidence, as after an upgrade
 * from a version without the record. A root whose indexed files are on the drive at its path is
 * recorded again and keeps its index; one whose drive cannot be confirmed reads unavailable with
 * "drive not confirmed after upgrade" and deletes nothing.
 *
 * <p>Real conditions, through {@link KnowledgeServer#start}: a first boot indexes the roots through
 * the person's add, which writes the record and its pending file; the server is closed, the record
 * alone is deleted or overwritten with bytes that are not a record, and the server boots again
 * over the same index.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
final class LostRootVolumeRecordTest {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  private static final long CHECK_MS = 120_000L;
  private static final String RECORD = "root-volumes.properties";
  private static final String PENDING = "root-volumes-pending.properties";
  private static final String UNCONFIRMED_REASON =
      "ROOT_NOT_DIRECTORY: drive not confirmed after upgrade";
  private static final String NOT_WRITABLE_YET = "Index runtime not available";

  @TempDir(factory = io.justsearch.indexerworker.services.VolumeIdentityTempDirFactory.class) Path tempDir;
  private KnowledgeServer server;
  private WorkerBootFixture.Layout layout;

  @BeforeEach
  void requireVolumeIdentity() throws Exception {
    // Native volume identity is required; the no-volume-identity campaign covers its absence.
    assumeTrue((WINDOWS || io.justsearch.indexerworker.services.VolumeIdentityTempDirFactory.hasLinuxVolumeIdentity(tempDir))
        && !Boolean.getBoolean("justsearch.test.noVolumeIdentity"),
        "the volume record exists only where the platform exposes a volume identity");
  }

  @AfterEach
  void tearDown() throws Exception {
    if (server != null) {
      server.close();
      server = null;
    }
  }

  @Test
  void aLostVolumeRecordIsSeededAgainOnEvidenceAndTheRootKeepsItsIndex() throws Exception {
    Path letters = Files.createDirectories(tempDir.resolve("letters"));
    Path letter = Files.writeString(letters.resolve("letter.txt"), "a capybara letter");
    List<RootBinding> roots = List.of(new RootBinding(letters, null));
    firstBootIndexes(roots, List.of(letter));
    Files.delete(recordFile(RECORD));

    bootAgain(roots);

    assertFalse(recordedVolumesUnder(letters).isEmpty(),
        "the root's volume is recorded again on the evidence of its indexed files");
    SyncDirectoryResponse synced = sync(letters);
    assertEquals("", synced.getError(), "the root stays available");
    assertEquals(0, synced.getFilesDeleted(), "nothing is deleted");
    assertTrue(searchHits("capybara").contains(PathNormalizer.normalizeKey(letter)),
        "the root's text stays searchable");
  }

  @Test
  void aLostVolumeRecordWhoseDriveIsNotConfirmedReadsUnconfirmedAndDeletesNothing()
      throws Exception {
    Path letters = Files.createDirectories(tempDir.resolve("letters"));
    Path letter = Files.writeString(letters.resolve("letter.txt"), "a capybara letter");
    Path draft = Files.writeString(letters.resolve("draft.txt"), "a quokka draft");
    List<RootBinding> roots = List.of(new RootBinding(letters, null));
    firstBootIndexes(roots, List.of(letter, draft));
    Files.delete(recordFile(RECORD));
    // Nothing indexed is on the drive at the root's path any more as it was indexed.
    Files.delete(letter);
    Files.writeString(draft, "a rewritten draft of another length");

    bootAgain(roots);

    assertEquals(List.of(), recordedVolumesUnder(letters),
        "nothing is recorded for a root whose drive is not confirmed");
    SyncDirectoryResponse refused = sync(letters);
    assertEquals(UNCONFIRMED_REASON, refused.getError(),
        "the root reads unavailable with the reason its drive is not confirmed");
    assertEquals(0, refused.getFilesDeleted(), "nothing is deleted");
    assertTrue(searchHits("capybara").contains(PathNormalizer.normalizeKey(letter)),
        "the indexed text of a file gone from the unconfirmed drive stays searchable");
  }

  @Test
  void aCorruptVolumeRecordIsDiscardedAndSeededAgainOnEvidence() throws Exception {
    Path letters = Files.createDirectories(tempDir.resolve("letters"));
    Path letter = Files.writeString(letters.resolve("letter.txt"), "a capybara letter");
    List<RootBinding> roots = List.of(new RootBinding(letters, null));
    firstBootIndexes(roots, List.of(letter));
    Files.write(recordFile(RECORD), garbage());

    bootAgain(roots);

    assertFalse(recordedVolumesUnder(letters).isEmpty(),
        "the corrupt record is replaced and the root recorded again on evidence");
    SyncDirectoryResponse synced = sync(letters);
    assertEquals("", synced.getError(), "the root stays available");
    assertEquals(0, synced.getFilesDeleted(), "nothing is deleted");
    assertTrue(searchHits("capybara").contains(PathNormalizer.normalizeKey(letter)),
        "the root's text stays searchable");
  }

  /** Bytes that are not a volume record: every byte value but the escape character, twice. */
  private static byte[] garbage() {
    byte[] bytes = new byte[510];
    int n = 0;
    for (int round = 0; round < 2; round++) {
      for (int b = 0; b < 256; b++) {
        if (b != '\\') {
          bytes[n++] = (byte) b;
        }
      }
    }
    return bytes;
  }

  /**
   * A first boot that declares {@code roots}, adds each one as the person does and waits until
   * every file of {@code files} is indexed; the server is then closed. The record and its pending
   * file exist then.
   */
  private void firstBootIndexes(List<RootBinding> roots, List<Path> files) throws Exception {
    layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "FAIL_CLOSED");
    bootAgain(roots);
    var ingest = server.appServices().ingestService();
    for (RootBinding binding : roots) {
      String rootPath = binding.path().toString();
      WatchRootResponse watched = ingest.watchRoot(
          added(WatchRootRequest.newBuilder().setRootPath(rootPath)).build(), CallContext.none());
      assertTrue(watched.getWatching(), "precondition: the root is added: " + rootPath);
      AtomicReference<ScanRootProgress> terminal = new AtomicReference<>();
      ingest.scanRoot(
          added(ScanRootRequest.newBuilder().setRootPath(rootPath)
              .setMode(ScanMode.SCAN_MODE_INITIAL)).build(),
          progress -> {
            if (progress.getComplete()) terminal.set(progress);
          },
          CallContext.none());
      assertEquals("", terminal.get() == null ? "NO_TERMINAL" : terminal.get().getTerminalReasonCode(),
          "precondition: the root is walked: " + rootPath);
      assertFalse(recordedVolumesUnder(binding.path()).isEmpty(),
          "precondition: the add records the root's volume: " + rootPath);
    }
    for (Path file : files) {
      String id = PathNormalizer.normalizeKey(file);
      String hash = SourceContentHash.sha256(file);
      assertTrue(awaitTrue(() -> hash.equals(server.lifecycleManagerForTests().documentFieldOps()
              .getDocumentField(id, SchemaFields.SOURCE_SHA256)), CHECK_MS),
          "precondition: the file is indexed: " + file);
    }
    server.close();
    server = null;
    assertTrue(Files.exists(recordFile(PENDING)), "precondition: the pending file exists");
  }

  private void bootAgain(List<RootBinding> roots) throws IOException {
    server = new KnowledgeServer(
        new io.justsearch.core.execution.TestEngineExecutors(),
        WorkerBootFixture.workerConfig(layout.dataDir()));
    server.bindBootRootBindings(roots);
    server.start();
  }

  /** The record file named {@code name} the Worker keeps under its data folder. */
  private Path recordFile(String name) throws IOException {
    try (Stream<Path> tree = Files.walk(layout.dataDir())) {
      List<Path> found =
          tree.filter(p -> p.getFileName().toString().equals(name)).toList();
      assertEquals(1, found.size(), "precondition: one " + name + " exists: " + found);
      return found.getFirst();
    }
  }

  /**
   * The periodic sync of {@code root}, which queues only files missing from the index, once the
   * server takes writes: until its writer is open a sync answers that the index runtime is not
   * available and does nothing.
   */
  private SyncDirectoryResponse sync(Path root) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CHECK_MS);
    while (true) {
      SyncDirectoryResponse response = server.appServices().ingestService().syncDirectory(
          SyncDirectoryRequest.newBuilder().setRootPath(root.toString()).build(), CallContext.none());
      if (!NOT_WRITABLE_YET.equals(response.getError()) || System.nanoTime() > deadline) {
        return response;
      }
      Thread.sleep(500L);
    }
  }

  /** {@code request} with the add marker set, on a build that has the marker. */
  private static <B extends com.google.protobuf.Message.Builder> B added(B request) {
    var marker = request.getDescriptorForType().findFieldByName("root_added");
    if (marker != null) {
      request.setField(marker, Boolean.TRUE);
    }
    return request;
  }

  /**
   * The folders at or under {@code folder} that the Worker's volume record names; bytes that are
   * not a record name none.
   */
  private List<String> recordedVolumesUnder(Path folder) throws IOException {
    String nativeKey = folder.toAbsolutePath().normalize().toString();
    String key = WINDOWS ? nativeKey.toLowerCase(Locale.ROOT) : nativeKey;
    List<String> recorded = new ArrayList<>();
    try (Stream<Path> files = Files.walk(layout.dataDir())) {
      for (Path file : files.filter(p -> p.getFileName().toString().equals(RECORD)).toList()) {
        Properties entries = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
          entries.load(in);
        }
        for (String name : entries.stringPropertyNames()) {
          String lower = WINDOWS ? name.toLowerCase(Locale.ROOT) : name;
          if ((lower.equals(key) || lower.startsWith(key + (WINDOWS ? "\\" : "/")))
              && entries.getProperty(name).startsWith(WINDOWS ? "windows-volume:" : "linux-volume-v1:")) {
            recorded.add(name);
          }
        }
      }
    }
    return recorded;
  }

  private List<String> searchHits(String word) {
    return server.appServices().searchService()
        .search(SearchRequest.newBuilder().setQuery(word).setLimit(10).build(), CallContext.none())
        .getResultsList().stream().map(SearchResult::getId).toList();
  }

  /** A condition read from the running server, which may throw while it is mid-change. */
  @FunctionalInterface
  private interface Condition {
    boolean holds() throws Exception;
  }

  private static boolean awaitTrue(Condition condition, long timeoutMs) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    while (System.nanoTime() < deadline) {
      try {
        if (condition.holds()) return true;
      } catch (IOException | RuntimeException notYet) {
        // The index or state is mid-change; look again.
      }
      Thread.sleep(500L);
    }
    return condition.holds();
  }
}
