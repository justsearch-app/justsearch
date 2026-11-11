/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY;
import static io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.READ_WRITE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A settings file that cannot be read is unavailable, never corrupt: nothing is moved aside, no
 * recovery is recorded and no defaults are returned. Contention waits for one whole version.
 */
class UiSettingsStoreUnavailableReadTest {
  private static final String KEY = "0194f72c-0000-7000-8000-000000000001";

  @TempDir Path directory;

  private enum Entry { LOAD, LOAD_SNAPSHOT, INSPECT, INSPECT_EXACT }

  /** What one entry point returned; fields it does not report stay null. */
  private record Observed(UiSettings settings, SettingsWitness witness, QueryRoleSelection queryRoles,
      String fileWitness) {}

  private static Observed read(UiSettingsStore store, Entry entry) {
    return switch (entry) {
      case LOAD -> new Observed(store.load(), null, null, null);
      case LOAD_SNAPSHOT -> snapshot(store.loadSnapshot(), null);
      case INSPECT -> snapshot(store.inspect(), null);
      case INSPECT_EXACT -> {
        var exact = store.inspectExact();
        yield snapshot(exact.snapshot(), exact.fileWitness());
      }
    };
  }

  private static Observed snapshot(UiSettingsStore.Snapshot snapshot, String fileWitness) {
    return new Observed(snapshot.settings(), snapshot.witness(), snapshot.queryRoles(), fileWitness);
  }

  // S1, S4, S5: the writer replaces the named file while the reader waits on a held lock.
  @Test
  void everyEntryPointWaitsThroughAReplacementAndReadsOneWholeVersion() throws Exception {
    byte[] oldBytes = envelope(111, new SettingsWitness(3, KEY), null);
    var roles = new QueryRoleSelection(QueryRoleSelection.Role.disabled(), QueryRoleSelection.Role.disabled());
    byte[] newBytes = envelope(222, new SettingsWitness(4, KEY), roles);
    for (Entry entry : Entry.values()) {
      Path file = directory.resolve(entry + "/settings.json");
      Files.createDirectories(file.getParent());
      Files.write(file, oldBytes);
      var store = new UiSettingsStore(READ_WRITE, file);

      Outcome<Observed> outcome = underContention(file, () -> read(store, entry), reader -> {
        Path staged = file.resolveSibling("settings.json.staged");
        Files.write(staged, newBytes);
        Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      });

      assertNull(outcome.failure(), entry + ": " + outcome.failure());
      Observed observed = outcome.value();
      assertEquals(222, observed.settings().getMaxTokens(), entry + " must read the replacement, not the stale version");
      if (entry != Entry.LOAD) {
        assertEquals(new SettingsWitness(4, KEY), observed.witness(), entry.toString());
        assertEquals(roles, observed.queryRoles(), entry.toString());
      }
      if (entry == Entry.INSPECT_EXACT) assertEquals(sha256(newBytes), observed.fileWitness());
      assertArrayEquals(newBytes, Files.readAllBytes(file));
      assertNoQuarantine(file);
      assertTrue(store.lastRecovery().isEmpty(), entry.toString());
    }
  }

  // S1, S4: an open denied for a moment, then granted, returns the readable version untouched.
  @Test
  void briefOpenDenialReturnsTheReadableVersionAtEveryEntryPoint() throws Exception {
    byte[] bytes = envelope(333, new SettingsWitness(5, KEY), null);
    for (Entry entry : Entry.values()) {
      Path real = directory.resolve(entry + "/settings.json");
      Files.createDirectories(real.getParent());
      Files.write(real, bytes);
      var files = new ScriptedFiles((attempt, path) -> {
        if (attempt <= 2) throw new AccessDeniedException(path.toString());
        return FileChannel.open(path, StandardOpenOption.READ);
      });
      var store = new UiSettingsStore(READ_WRITE, files.wrap(real));

      Observed observed = assertDoesNotThrow(() -> read(store, entry), entry.toString());

      assertEquals(333, observed.settings().getMaxTokens(), entry.toString());
      if (entry != Entry.LOAD) assertEquals(new SettingsWitness(5, KEY), observed.witness(), entry.toString());
      if (entry == Entry.INSPECT_EXACT) assertEquals(sha256(bytes), observed.fileWitness());
      assertEquals(3, files.reads.get(), entry + " must retry the denied open");
      assertArrayEquals(bytes, Files.readAllBytes(real));
      assertNoQuarantine(real);
      assertTrue(store.lastRecovery().isEmpty(), entry.toString());
    }
  }

  // S2, S4, S6 and P1 (disappearance after contention is not a fresh store).
  @Test
  void lastingInjectedReadFailuresAreUnavailableAtEveryEntryPoint() throws Exception {
    Map<String, Opener> failures = new java.util.LinkedHashMap<>();
    failures.put("open", (attempt, path) -> { throw new FileSystemException(path.toString(), null, "injected open failure"); });
    for (String stage : List.of("lock", "read", "close")) {
      failures.put(stage, (attempt, path) -> new CheckedChannel(
          FileChannel.open(path, StandardOpenOption.READ), stage, new AccessDeniedException(path.toString())));
    }
    failures.put("missing-after-contention", (attempt, path) -> {
      if (attempt > 1) throw new NoSuchFileException(path.toString());
      return new CheckedChannel(FileChannel.open(path, StandardOpenOption.READ), "unavailable", null);
    });
    byte[] bytes = envelope(444, new SettingsWitness(6, KEY), null);
    for (var failure : failures.entrySet()) {
      for (Entry entry : Entry.values()) {
        String label = failure.getKey() + "/" + entry;
        Path real = directory.resolve(failure.getKey() + "/" + entry + "/settings.json");
        Files.createDirectories(real.getParent());
        Files.write(real, bytes);
        var files = new ScriptedFiles(failure.getValue());
        var store = new UiSettingsStore(READ_WRITE, files.wrap(real));

        assertUnavailable(store, entry, label);
        assertArrayEquals(bytes, Files.readAllBytes(real), label);
        assertNoQuarantine(real);
        assertTrue(store.lastRecovery().isEmpty(), label + " must not record a recovery");
        if (!failure.getKey().equals("open")) assertTrue(files.reads.get() >= 1, label);
        if (List.of("lock", "read", "close").contains(failure.getKey())) {
          assertEquals(1, files.reads.get(), label + ": denial after opening is not retried");
        }

        files.opener = (attempt, path) -> FileChannel.open(path, StandardOpenOption.READ);
        assertEquals(444, assertDoesNotThrow(() -> read(store, entry), label).settings().getMaxTokens(),
            label + ": later read is unchanged");
      }
    }
  }

  // S2, S4, S6: a lasting denied open spends the shared budget and stays unavailable.
  @Test
  void lastingOpenDenialIsUnavailable() throws Exception {
    Path real = directory.resolve("settings.json");
    byte[] bytes = envelope(555, new SettingsWitness(7, KEY), null);
    Files.write(real, bytes);
    var files = new ScriptedFiles((attempt, path) -> { throw new AccessDeniedException(path.toString()); });
    var store = new UiSettingsStore(READ_WRITE, files.wrap(real));

    long started = System.nanoTime();
    assertUnavailable(store, Entry.LOAD, "denied");
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 1_500, "denial must be retried");
    assertTrue(files.reads.get() >= 2);
    assertArrayEquals(bytes, Files.readAllBytes(real));
    assertNoQuarantine(real);
    assertTrue(store.lastRecovery().isEmpty());
  }

  // S2, S4, S6 with a real permission denial.
  @Test
  @DisabledOnOs(OS.WINDOWS)
  void permissionDeniedFileIsUnavailableAtEveryEntryPoint() throws Exception {
    Path file = directory.resolve("settings.json");
    byte[] bytes = envelope(666, new SettingsWitness(8, KEY), null);
    Files.write(file, bytes);
    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
    Files.setPosixFilePermissions(file, Set.of());
    try {
      assumeTrue(!Files.isReadable(file), "process bypasses file permissions; denial fixture unavailable");
      for (Entry entry : Entry.values()) {
        var store = new UiSettingsStore(READ_WRITE, file);
        assertUnavailable(store, entry, entry.toString());
        assertTrue(Files.exists(file), entry + ": the file must stay in place");
        assertNoQuarantine(file);
        assertTrue(store.lastRecovery().isEmpty(), entry.toString());
      }
    } finally {
      // Before the repair the file is moved aside; restoring must not mask that assertion.
      if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) Files.setPosixFilePermissions(file, permissions);
    }
    assertArrayEquals(bytes, Files.readAllBytes(file));
    assertEquals(666, new UiSettingsStore(READ_WRITE, file).load().getMaxTokens());
  }

  // S2, S4, S6: a directory standing at the settings path is not moved aside as corrupt content.
  @Test
  void directoryAtTheSettingsPathIsUnavailableAtEveryEntryPoint() throws Exception {
    Path file = directory.resolve("settings.json");
    Files.createDirectory(file);
    Files.writeString(file.resolve("inside"), "kept");
    for (Entry entry : Entry.values()) {
      var store = new UiSettingsStore(READ_WRITE, file);
      assertUnavailable(store, entry, entry.toString());
      assertTrue(Files.isDirectory(file), entry + ": the directory must stay in place");
      assertEquals("kept", Files.readString(file.resolve("inside")));
      assertNoQuarantine(file);
      assertTrue(store.lastRecovery().isEmpty(), entry.toString());
    }
  }

  // P1: a parent that is not a directory is not a fresh store and is not recovered to defaults.
  @Test
  void nonDirectoryParentIsNotAFreshStore() throws Exception {
    Path parent = directory.resolve("not-a-directory");
    Files.writeString(parent, "obstruction");
    for (Entry entry : Entry.values()) {
      var store = new UiSettingsStore(READ_WRITE, parent.resolve("settings.json"));
      assertThrows(UncheckedIOException.class, () -> read(store, entry), entry.toString());
      assertEquals("obstruction", Files.readString(parent));
      assertTrue(store.lastRecovery().isEmpty(), entry.toString());
    }
  }

  // S3: an interrupt before reading ends the read, keeps the flag and recovers nothing.
  @Test
  void interruptBeforeReadingKeepsTheFlagAndRecoversNothing() throws Exception {
    byte[] bytes = envelope(777, new SettingsWitness(9, KEY), null);
    for (Entry entry : Entry.values()) {
      Path file = directory.resolve(entry + "/settings.json");
      Files.createDirectories(file.getParent());
      Files.write(file, bytes);
      var store = new UiSettingsStore(READ_WRITE, file);
      Throwable failure;
      boolean retained;
      Thread.currentThread().interrupt();
      try {
        failure = assertThrows(RuntimeException.class, () -> read(store, entry), entry.toString());
      } finally {
        retained = Thread.interrupted();
      }
      assertTrue(retained, entry + " must keep the interrupt flag");
      assertInstanceOf(UncheckedIOException.class, failure, entry.toString());
      assertInstanceOf(InterruptedIOException.class, failure.getCause(), entry.toString());
      assertArrayEquals(bytes, Files.readAllBytes(file));
      assertNoQuarantine(file);
      assertTrue(store.lastRecovery().isEmpty(), entry.toString());
    }
  }

  // S3: an interrupt during contention ends the wait, keeps the flag and recovers nothing.
  @Test
  void interruptDuringContentionKeepsTheFlagAndRecoversNothing() throws Exception {
    byte[] bytes = envelope(888, new SettingsWitness(10, KEY), null);
    for (Entry entry : Entry.values()) {
      Path file = directory.resolve(entry + "/settings.json");
      Files.createDirectories(file.getParent());
      Files.write(file, bytes);
      var store = new UiSettingsStore(READ_WRITE, file);

      Outcome<Observed> outcome = underContention(file, () -> read(store, entry), Thread::interrupt);

      assertInstanceOf(UncheckedIOException.class, outcome.failure(), entry + " must not return a value");
      assertInstanceOf(InterruptedIOException.class, outcome.failure().getCause(), entry.toString());
      assertTrue(outcome.interruptedAfter(), entry + " must keep the interrupt flag");
      assertArrayEquals(bytes, Files.readAllBytes(file));
      assertNoQuarantine(file);
      assertTrue(store.lastRecovery().isEmpty(), entry.toString());
    }
  }

  // S6, P6: a failed read neither clears nor replaces a real recovery notice.
  @Test
  void failedReadKeepsTheExistingRecoveryNotice() throws Exception {
    Path real = directory.resolve("settings.json");
    Files.writeString(real, "{not-json");
    var files = new ScriptedFiles((attempt, path) -> FileChannel.open(path, StandardOpenOption.READ));
    var store = new UiSettingsStore(READ_WRITE, files.wrap(real));
    AtomicBoolean cleared = new AtomicBoolean();
    store.setOnRecoveryCleared(() -> cleared.set(true));
    store.load();
    var recovery = store.lastRecovery().orElseThrow();
    List<Path> preserved = quarantine(real);
    assertEquals(1, preserved.size());

    byte[] replacement = envelope(999, new SettingsWitness(1, KEY), null);
    Files.write(real, replacement);
    files.opener = (attempt, path) -> new CheckedChannel(
        FileChannel.open(path, StandardOpenOption.READ), "read", new IOException("injected read failure"));

    assertUnavailable(store, Entry.LOAD, "load after recovery");
    assertEquals(recovery, store.lastRecovery().orElseThrow(), "the real notice stays until the owner clears it");
    assertFalse(cleared.get());
    assertEquals(preserved, quarantine(real), "no second quarantine is created");
    assertArrayEquals(replacement, Files.readAllBytes(real));
  }

  // P1: a truly absent store, also under a proven-absent parent, is still a fresh revision-zero store.
  @Test
  void absentStoreStillReturnsOrdinaryDefaults() throws Exception {
    for (Path file : List.of(directory.resolve("settings.json"), directory.resolve("missing/ui/settings.json"))) {
      var store = new UiSettingsStore(READ_WRITE, file);
      var snapshot = store.loadSnapshot();
      assertEquals(new SettingsWitness(0, null), snapshot.witness());
      assertTrue(snapshot.settings().getIndexingPause() == null
          || !snapshot.settings().getIndexingPause().paused(), "a fresh store is not a recovery");
      assertEquals(new SettingsWitness(0, null), store.inspect().witness());
      assertEquals("absent", store.inspectExact().fileWitness());
      assertTrue(store.lastRecovery().isEmpty());
    }
  }

  // P2: real corruption read after contention is still quarantined with its bytes and paused defaults.
  @Test
  void corruptionAfterContentionIsStillQuarantined() throws Exception {
    Path file = directory.resolve("settings.json");
    Files.writeString(file, "{not-json");
    var store = new UiSettingsStore(READ_WRITE, file);
    assertThrows(CorruptDurableStoreException.class, store::inspect);
    assertThrows(CorruptDurableStoreException.class, store::inspectExact);
    assertTrue(Files.exists(file), "inspection never quarantines");

    Outcome<UiSettings> outcome = underContention(file, store::load, reader -> {});

    assertNull(outcome.failure());
    assertTrue(outcome.value().getIndexingPause().paused());
    assertFalse(Files.exists(file));
    List<Path> preserved = quarantine(file);
    assertEquals(1, preserved.size());
    assertEquals("{not-json", Files.readString(preserved.get(0)));
    assertTrue(store.lastRecovery().isPresent());
  }

  // P3: a quarantine whose move fails stays fatal and claims nothing was preserved.
  @Test
  void failedQuarantineMoveStaysFatal() throws Exception {
    Path real = directory.resolve("settings.json");
    Path earlier = directory.resolve("settings.json.corrupt-earlier");
    Files.writeString(earlier, "earlier evidence");
    Files.writeString(real, "{not-json");
    var files = new ScriptedFiles((attempt, path) -> FileChannel.open(path, StandardOpenOption.READ));
    files.failMoves = true;
    var store = new UiSettingsStore(READ_WRITE, files.wrap(real));

    var failure = assertThrows(CorruptDurableStoreException.class, store::load);

    assertEquals(1, failure.getSuppressed().length, "the failed move is attached");
    assertEquals("{not-json", Files.readString(real));
    assertEquals(List.of(earlier), quarantine(real));
    assertEquals("earlier evidence", Files.readString(earlier));
    assertTrue(store.lastRecovery().isEmpty());
  }

  // P4: a future schema read after contention still fails without migration, quarantine or defaults.
  @Test
  void futureSchemaAfterContentionIsStillRefused() throws Exception {
    Path file = directory.resolve("settings.json");
    String future = "{\"schemaVersion\":99,\"settings\":{\"maxTokens\":321}}";
    Files.writeString(file, future);
    for (Entry entry : Entry.values()) {
      var store = new UiSettingsStore(READ_WRITE, file);
      Outcome<Observed> outcome = underContention(file, () -> read(store, entry), reader -> {});
      assertInstanceOf(UnsupportedStoreVersionException.class, outcome.failure(), entry.toString());
      assertEquals(future, Files.readString(file));
      assertNoQuarantine(file);
      assertTrue(store.lastRecovery().isEmpty());
    }
  }

  // P8: legacy bytes read after contention are preserved once and migrated idempotently.
  @Test
  void legacyMigrationAfterContentionPreservesTheOriginalOnce() throws Exception {
    Path file = directory.resolve("settings.json");
    String legacy = "{\"schemaVersion\":2,\"settings\":{\"maxTokens\":4321}}";
    Files.writeString(file, legacy);
    var store = new UiSettingsStore(READ_WRITE, file);

    Outcome<UiSettings> first = underContention(file, store::load, reader -> {});
    Outcome<UiSettings> second = underContention(file, store::load, reader -> {});

    for (var outcome : List.of(first, second)) {
      assertNull(outcome.failure());
      assertEquals(4321, outcome.value().getMaxTokens());
    }
    assertEquals(legacy, Files.readString(directory.resolve("settings.v2.bak.json")));
    assertEquals(legacy, Files.readString(file));
    assertNoQuarantine(file);
  }

  // P9: in-memory mode never touches the configured path, even when it cannot be read.
  @Test
  void inMemoryModeDoesNotTouchAnUnreadablePath() throws Exception {
    Path real = directory.resolve("settings.json");
    Files.createDirectory(real);
    var files = new ScriptedFiles((attempt, path) -> { throw new AccessDeniedException(path.toString()); });
    var store = new UiSettingsStore(IN_MEMORY, files.wrap(real));

    assertEquals(new UiSettings().getMaxTokens(), store.load().getMaxTokens());
    assertEquals(new SettingsWitness(0, null), store.loadSnapshot().witness());
    assertEquals(new SettingsWitness(0, null), store.inspect().witness());
    assertEquals(0, files.reads.get());
    assertTrue(Files.isDirectory(real));
    assertNoQuarantine(real);
  }

  // P7: a reset cannot be prepared from an unavailable inspection.
  @Test
  void resetPreparationRefusesAnUnavailableInspection() throws Exception {
    Path real = directory.resolve("settings.json");
    Files.write(real, envelope(123, new SettingsWitness(2, KEY), null));
    var files = new ScriptedFiles((attempt, path) -> new CheckedChannel(
        FileChannel.open(path, StandardOpenOption.READ), "read", new IOException("injected read failure")));
    var store = new UiSettingsStore(READ_WRITE, files.wrap(real));
    var service = new SettingsServiceImpl(store, mock(OperationAttemptRunner.class));

    var refused = assertThrows(OperationPreparationRefused.class, () -> service.prepareReset("{}"));

    assertEquals("SETTINGS_RECOVERY_REQUIRED", refused.refusal().errorCode().orElseThrow());
    assertTrue(Files.exists(real));
    assertNoQuarantine(real);
  }

  private static void assertUnavailable(UiSettingsStore store, Entry entry, String label) {
    RuntimeException failure = assertThrows(RuntimeException.class, () -> read(store, entry),
        label + " must not return settings");
    assertInstanceOf(UncheckedIOException.class, failure, label + " is unavailable, not corrupt: " + failure);
    assertNotNull(failure.getMessage());
    assertTrue(failure.getMessage().startsWith("Settings unavailable"), label + ": " + failure.getMessage());
  }

  private static void assertNoQuarantine(Path file) throws IOException {
    assertEquals(List.of(), quarantine(file), "nothing may be moved aside");
  }

  private static List<Path> quarantine(Path file) throws IOException {
    String prefix = file.getFileName() + ".corrupt-";
    try (Stream<Path> siblings = Files.list(file.toAbsolutePath().getParent())) {
      return siblings.filter(path -> path.getFileName().toString().startsWith(prefix)).sorted().toList();
    }
  }

  private byte[] envelope(int maxTokens, SettingsWitness witness, QueryRoleSelection roles) throws IOException {
    Path scratch = Files.createTempDirectory(directory, "envelope");
    var writer = new UiSettingsStore(READ_WRITE, scratch.resolve("settings.json"));
    var settings = new UiSettings();
    settings.setMaxTokens(maxTokens);
    writer.replacePrepared(writer.prepareExact(settings, witness, roles));
    return Files.readAllBytes(scratch.resolve("settings.json"));
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  @FunctionalInterface
  private interface ReaderAction {
    void run(Thread reader) throws Exception;
  }

  @FunctionalInterface
  private interface Read<T> {
    T call() throws Exception;
  }

  private record Outcome<T>(T value, Throwable failure, boolean interruptedAfter) {}

  /**
   * Runs {@code read} on its own thread while this JVM holds an exclusive lock on {@code file}, runs
   * {@code whileWaiting} once the reader waits (or has already finished), then releases the lock.
   */
  private static <T> Outcome<T> underContention(Path file, Read<T> read, ReaderAction whileWaiting)
      throws Exception {
    var result = new CompletableFuture<Outcome<T>>();
    Thread reader = new Thread(() -> {
      try {
        T value = read.call();
        result.complete(new Outcome<>(value, null, Thread.currentThread().isInterrupted()));
      } catch (Throwable failure) {
        result.complete(new Outcome<>(null, failure, Thread.currentThread().isInterrupted()));
      }
    }, "settings-contended-reader");
    try (FileChannel holder = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock held = holder.lock()) {
      assertTrue(held.isValid());
      reader.start();
      awaitWaitingOrDone(reader);
      whileWaiting.run(reader);
    }
    try {
      return result.get(10, TimeUnit.SECONDS);
    } catch (ExecutionException unexpected) {
      throw new AssertionError(unexpected.getCause());
    } finally {
      reader.interrupt();
      reader.join(5_000);
    }
  }

  private static void awaitWaitingOrDone(Thread reader) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      Thread.State state = reader.getState();
      if (state == Thread.State.TIMED_WAITING || state == Thread.State.TERMINATED) return;
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
    }
  }

  @FunctionalInterface
  private interface Opener {
    FileChannel open(int attempt, Path real) throws IOException;
  }

  /**
   * The default file system, except that plain reads of the file open through a script. Both the
   * channel and byte-channel entry points are scripted, so the check does not depend on which one a
   * reader uses. No production test hook is needed.
   */
  private static final class ScriptedFiles extends FileSystemProvider {
    private final FileSystemProvider real = FileSystems.getDefault().provider();
    private final FileSystem realFs = FileSystems.getDefault();
    private final AtomicInteger reads = new AtomicInteger();
    private volatile Opener opener;
    private volatile boolean failMoves;
    private final FileSystem fileSystem = new FileSystem() {
      @Override public FileSystemProvider provider() { return ScriptedFiles.this; }
      @Override public void close() { throw new UnsupportedOperationException(); }
      @Override public boolean isOpen() { return true; }
      @Override public boolean isReadOnly() { return false; }
      @Override public String getSeparator() { return realFs.getSeparator(); }
      @Override public Iterable<Path> getRootDirectories() { throw new UnsupportedOperationException(); }
      @Override public Iterable<FileStore> getFileStores() { throw new UnsupportedOperationException(); }
      @Override public Set<String> supportedFileAttributeViews() { return realFs.supportedFileAttributeViews(); }
      @Override public Path getPath(String first, String... more) { return wrap(realFs.getPath(first, more)); }
      @Override public PathMatcher getPathMatcher(String syntax) { return realFs.getPathMatcher(syntax); }
      @Override public UserPrincipalLookupService getUserPrincipalLookupService() { throw new UnsupportedOperationException(); }
      @Override public WatchService newWatchService() { throw new UnsupportedOperationException(); }
    };

    private ScriptedFiles(Opener opener) {
      this.opener = opener;
    }

    Path wrap(Path path) {
      InvocationHandler handler = new Wrapped(path);
      return (Path) Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[] {Path.class}, handler);
    }

    private final class Wrapped implements InvocationHandler {
      private final Path path;

      private Wrapped(Path path) {
        this.path = path;
      }

      @Override
      public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
          case "getFileSystem": return fileSystem;
          case "equals": return args[0] instanceof Path other && path.equals(unwrap(other));
          case "hashCode": return path.hashCode();
          case "toString": return path.toString();
          default: break;
        }
        Object[] actual = args == null ? null : new Object[args.length];
        for (int i = 0; actual != null && i < args.length; i++) {
          actual[i] = args[i] instanceof Path wrapped ? unwrap(wrapped) : args[i];
        }
        try {
          Object result = method.invoke(path, actual);
          return result instanceof Path resolved ? wrap(resolved) : result;
        } catch (InvocationTargetException failure) {
          throw failure.getCause();
        }
      }
    }

    private static Path unwrap(Path path) {
      if (Proxy.isProxyClass(path.getClass()) && Proxy.getInvocationHandler(path) instanceof Wrapped wrapped) {
        return wrapped.path;
      }
      return path;
    }

    private static boolean plainRead(Set<? extends OpenOption> options) {
      return options.isEmpty() || options.equals(Set.of(StandardOpenOption.READ));
    }

    @Override
    public FileChannel newFileChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
        throws IOException {
      if (plainRead(options)) return opener.open(reads.incrementAndGet(), unwrap(path));
      return real.newFileChannel(unwrap(path), options, attrs);
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
        throws IOException {
      if (plainRead(options)) return opener.open(reads.incrementAndGet(), unwrap(path));
      return real.newByteChannel(unwrap(path), options, attrs);
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
      if (failMoves) throw new IOException("injected move failure");
      real.move(unwrap(source), unwrap(target), options);
    }

    @Override public String getScheme() { return "scripted-settings"; }
    @Override public FileSystem newFileSystem(URI uri, Map<String, ?> env) { throw new UnsupportedOperationException(); }
    @Override public FileSystem getFileSystem(URI uri) { return fileSystem; }
    @Override public Path getPath(URI uri) { return wrap(real.getPath(uri)); }
    @Override public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
        throws IOException { return real.newDirectoryStream(unwrap(dir), filter); }
    @Override public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException { real.createDirectory(unwrap(dir), attrs); }
    @Override public void createLink(Path link, Path existing) throws IOException { real.createLink(unwrap(link), unwrap(existing)); }
    @Override public void delete(Path path) throws IOException { real.delete(unwrap(path)); }
    @Override public void copy(Path source, Path target, CopyOption... options) throws IOException { real.copy(unwrap(source), unwrap(target), options); }
    @Override public boolean isSameFile(Path path, Path other) throws IOException { return real.isSameFile(unwrap(path), unwrap(other)); }
    @Override public boolean isHidden(Path path) throws IOException { return real.isHidden(unwrap(path)); }
    @Override public FileStore getFileStore(Path path) throws IOException { return real.getFileStore(unwrap(path)); }
    @Override public void checkAccess(Path path, AccessMode... modes) throws IOException { real.checkAccess(unwrap(path), modes); }
    @Override public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) { return real.getFileAttributeView(unwrap(path), type, options); }
    @Override public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
        throws IOException { return real.readAttributes(unwrap(path), type, options); }
    @Override public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
        throws IOException { return real.readAttributes(unwrap(path), attributes, options); }
    @Override public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
        throws IOException { real.setAttribute(unwrap(path), attribute, value, options); }
  }

  /** A real read channel whose lock, read or close boundary can independently fail. */
  private static final class CheckedChannel extends FileChannel {
    private final FileChannel delegate;
    private final String stage;
    private final IOException failure;

    private CheckedChannel(FileChannel delegate, String stage, IOException failure) {
      this.delegate = delegate;
      this.stage = stage;
      this.failure = failure;
    }

    @Override public FileLock tryLock(long position, long size, boolean shared) throws IOException {
      if (stage.equals("lock")) throw failure;
      if (stage.equals("unavailable")) return null;
      return delegate.tryLock(position, size, shared);
    }

    @Override public int read(ByteBuffer dst) throws IOException {
      if (stage.equals("read")) throw failure;
      return delegate.read(dst);
    }

    @Override protected void implCloseChannel() throws IOException {
      delegate.close();
      if (stage.equals("close")) throw failure;
    }

    @Override public long position() throws IOException { return delegate.position(); }
    @Override public long size() throws IOException { return delegate.size(); }
    @Override public FileChannel position(long position) throws IOException { delegate.position(position); return this; }
    @Override public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
      if (stage.equals("read")) throw failure;
      return delegate.read(dsts, offset, length);
    }
    @Override public int read(ByteBuffer dst, long position) throws IOException {
      if (stage.equals("read")) throw failure;
      return delegate.read(dst, position);
    }
    @Override public int write(ByteBuffer src) { throw new UnsupportedOperationException(); }
    @Override public long write(ByteBuffer[] srcs, int offset, int length) { throw new UnsupportedOperationException(); }
    @Override public int write(ByteBuffer src, long position) { throw new UnsupportedOperationException(); }
    @Override public FileChannel truncate(long size) { throw new UnsupportedOperationException(); }
    @Override public void force(boolean metaData) { throw new UnsupportedOperationException(); }
    @Override public long transferTo(long position, long count, WritableByteChannel target) { throw new UnsupportedOperationException(); }
    @Override public long transferFrom(ReadableByteChannel src, long position, long count) { throw new UnsupportedOperationException(); }
    @Override public MappedByteBuffer map(MapMode mode, long position, long size) { throw new UnsupportedOperationException(); }
    @Override public FileLock lock(long position, long size, boolean shared) { throw new UnsupportedOperationException(); }
  }
}
