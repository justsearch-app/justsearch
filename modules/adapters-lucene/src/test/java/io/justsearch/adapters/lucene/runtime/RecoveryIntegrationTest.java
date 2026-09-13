package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import io.justsearch.indexing.runtime.IndexOpenGuard;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

/**
 * Tempdoc 406 Phase 4c (C1 + C2): recovery-path tests for {@code RuntimeSession}.
 *
 * <p>The recovery code in {@code RuntimeSession.openComponentsWithRecovery} was
 * translated from {@code LuceneLifecycleManager.startGuarded} during Phase 2-3.
 * The translation compiled and the unit suite passed, but no test exercised
 * the actual recovery branches with real fixtures. This file closes that gap:
 *
 * <ul>
 *   <li>{@link #corruptIndexAutoRecoveryProducesBackupAndFreshIndex}: deletes a
 *       Lucene segment file to trigger {@code NoSuchFileException} on a segment
 *       (classified as {@code CORRUPT_INDEX}), opens with {@code
 *       index.auto_recovery=true}, asserts the corrupted directory is moved to
 *       a {@code .bak-<ts>} sibling and a fresh empty index is opened.</li>
 *   <li>{@link #schemaMismatchRebuildBackupFirstProducesBackupAndFreshIndex}:
 *       seeds an index with a "title" field as {@code text}, then reopens with
 *       a schema where "title" is {@code keyword}. {@code
 *       checkFieldSchemaCompatibility} detects the IndexOptions/DocValuesType
 *       mismatch and throws {@code SCHEMA_MISMATCH}; with policy {@code
 *       REBUILD_BACKUP_FIRST} the recovery path runs.</li>
 *   <li>{@link #corruptIndexWithoutAutoRecoveryFailsFast}: same corruption as
 *       C1, but with {@code index.auto_recovery=false}; asserts the
 *       {@code IndexRuntimeIOException} surfaces unmodified.</li>
 * </ul>
 */
class RecoveryIntegrationTest extends RuntimeTestBase {

  // ==========================================================================
  // C1 — CORRUPT_INDEX auto-recovery
  // ==========================================================================

  @Test
  void corruptIndexAutoRecoveryProducesBackupAndFreshIndex() throws Exception {
    Path dataRoot = dataDir();
    Path indexPath = dataRoot.resolve("idx");
    Files.createDirectories(indexPath);

    IndexSchema schema = buildSchemaWithDim(4);

    // Seed: open, write a doc, commit, close. Leaves valid Lucene segment files.
    RunningRuntime seed = schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).withFallbackIndexPath(dataRoot).open();
    seed.indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "doc-1",
                    SchemaFields.DOC_UID, "doc-1#0",
                    SchemaFields.CONTENT, "before-corruption")));
    seed.commitOps().commitAndTrack();
    seed.close();

    // Confirm we have segment files to corrupt.
    assertTrue(
        listSegmentFiles(indexPath).count() > 0,
        "seed must produce Lucene segment files before corruption step");

    // Corrupt by removing the segments_N file. ComponentsFactory will raise
    // NoSuchFileException on a segment file, which classifyIOException maps to
    // CORRUPT_INDEX.
    corruptSegmentsFile(indexPath);

    // Item 17 (critical-analysis fix): wrap the real parity guard with a counter so we can
    // assert the recovery path actually went through the parity guard's re-classification
    // (Gap D fix), not just through the fallback open path inside ComponentsFactory.
    GuardSpy guardSpy =
        new GuardSpy(
            new IndexMetadataParityGuard(
                () -> indexPath, () -> schema.metadataSourceSupplier().get().build()));

    // Reopen with auto_recovery=true. Recovery moves indexPath → indexPath.bak-<ts>
    // and creates a fresh empty index at indexPath.
    ResolvedConfig recoveryCfg = resolvedConfigWith(autoRecoveryYaml(true));
    RunningRuntime recovered =
        schema
            .atPath(indexPath).withExecutorRegistrations(testLuceneExecutors())
            .withFallbackIndexPath(dataRoot)
            .withConfig(recoveryCfg)
            .withIndexOpenGuard(guardSpy)
            .open();

    // The guard is still on the open path (wiring proof), and it deliberately does NOT raise for
    // corruption any more. Tempdoc 915 B4: the parity inspection answers "does the last commit
    // record this runtime's shape?", and an unreadable commit leaves that question UNANSWERED
    // rather than answering "no". It used to re-classify the read failure as CORRUPT_INDEX, which
    // was harmless from inside the open and fatal from the pre-open call site added in the same
    // tempdoc - that site sits outside openComponentsWithRecovery, so raising there killed the
    // Worker on an index the product recovers from. ComponentsFactory classifies corruption on the
    // reader/writer open independently, which is what the rest of this test goes on to prove:
    // backup taken, fresh index served, writes accepted.
    assertTrue(
        guardSpy.invocations.get() >= 1,
        "parity guard should have been invoked during recovery; got: " + guardSpy.invocations.get());
    assertFalse(
        guardSpy.observedCorruptionClassification.get(),
        "the parity guard must not raise CORRUPT_INDEX: recovery is the OPEN path's job and the"
            + " same throw would kill the boot from the pre-open call site. Observed exceptions: "
            + guardSpy.observedExceptions);

    // Assert: a sibling backup exists.
    List<Path> backups = listSiblingBackups(indexPath, "idx.bak-");
    assertEquals(
        1,
        backups.size(),
        () -> "expected exactly one idx.bak-<ts> sibling, got: " + backups);
    assertTrue(Files.isDirectory(backups.get(0)), "backup must be a directory");

    // Assert: fresh runtime is empty (the corrupted doc is not visible).
    SearchResult result =
        recovered
            .readPathOps()
            .search(new MatchAllDocsQuery(), 10, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    assertEquals(0, result.hits().size(), "rebuilt index must be empty");

    // And we can write a new doc on the fresh index.
    recovered
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "doc-2",
                    SchemaFields.DOC_UID, "doc-2#0",
                    SchemaFields.CONTENT, "after-recovery")));
    recovered.commitOps().commitAndTrack();
    recovered.commitOps().maybeRefreshBlocking();
    SearchResult afterWrite =
        recovered
            .readPathOps()
            .search(new MatchAllDocsQuery(), 10, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    assertEquals(1, afterWrite.hits().size(), "rebuilt index must accept new writes");
    recovered.close();
  }

  @Test
  void corruptIndexWithoutAutoRecoveryFailsFast() throws Exception {
    Path dataRoot = dataDir();
    Path indexPath = dataRoot.resolve("idx-noauto");
    Files.createDirectories(indexPath);

    IndexSchema schema = buildSchemaWithDim(4);

    RunningRuntime seed = schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).withFallbackIndexPath(dataRoot).open();
    seed.indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "doc-1",
                    SchemaFields.DOC_UID, "doc-1#0",
                    SchemaFields.CONTENT, "before-corruption")));
    seed.commitOps().commitAndTrack();
    seed.close();

    corruptSegmentsFile(indexPath);

    ResolvedConfig noRecoveryCfg = resolvedConfigWith(autoRecoveryYaml(false));

    IndexRuntimeIOException ex =
        assertThrows(
            IndexRuntimeIOException.class,
            () ->
                schema
                    .atPath(indexPath).withExecutorRegistrations(testLuceneExecutors())
                    .withFallbackIndexPath(dataRoot)
                    .withConfig(noRecoveryCfg)
                    .open());
    assertEquals(
        IndexRuntimeIOException.Reason.CORRUPT_INDEX,
        ex.reason(),
        "Without auto_recovery, CORRUPT_INDEX must propagate unchanged");

    // No sibling backup was produced.
    assertEquals(
        0,
        listSiblingBackups(indexPath, "idx-noauto.bak-").size(),
        "no backup must be created when auto_recovery is disabled");
  }

  // ==========================================================================
  // C2 — SCHEMA_MISMATCH REBUILD_BACKUP_FIRST recovery
  // ==========================================================================

  @Test
  void schemaMismatchRebuildBackupFirstProducesBackupAndFreshIndex() throws Exception {
    Path dataRoot = dataDir();
    Path indexPath = dataRoot.resolve("schema-idx");
    Files.createDirectories(indexPath);

    // Schema A: title is a TEXT field (IndexOptions=DOCS_AND_FREQS_AND_POSITIONS,
    // DocValuesType=NONE). Seed the index with one doc that exercises the field.
    IndexSchema schemaA = buildSchemaWithTitleType("text");
    RunningRuntime seed = schemaA.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).withFallbackIndexPath(dataRoot).open();
    seed.indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "doc-1",
                    SchemaFields.DOC_UID, "doc-1#0",
                    SchemaFields.CONTENT, "body",
                    "title", "hello world")));
    seed.commitOps().commitAndTrack();
    seed.close();

    // Schema B: title is now a KEYWORD field (IndexOptions=DOCS, DocValuesType=SORTED).
    // checkFieldSchemaCompatibility must detect the FieldInfo mismatch on reopen.
    IndexSchema schemaB = buildSchemaWithTitleType("keyword");
    ResolvedConfig rebuildCfg = resolvedConfigWith(schemaMismatchPolicyYaml("REBUILD_BACKUP_FIRST"));

    RunningRuntime recovered =
        schemaB
            .atPath(indexPath).withExecutorRegistrations(testLuceneExecutors())
            .withFallbackIndexPath(dataRoot)
            .withConfig(rebuildCfg)
            .open();

    // Assert: sibling backup exists.
    List<Path> backups = listSiblingBackups(indexPath, "schema-idx.bak-");
    assertEquals(
        1,
        backups.size(),
        () -> "expected exactly one schema-idx.bak-<ts> sibling, got: " + backups);

    // Assert: fresh runtime is empty.
    SearchResult result =
        recovered
            .readPathOps()
            .search(new MatchAllDocsQuery(), 10, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    assertEquals(0, result.hits().size(), "rebuilt index must be empty after schema mismatch");

    // And the new schema is writable.
    recovered
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "doc-2",
                    SchemaFields.DOC_UID, "doc-2#0",
                    SchemaFields.CONTENT, "body",
                    "title", "post-rebuild")));
    recovered.commitOps().commitAndTrack();
    recovered.commitOps().maybeRefreshBlocking();
    SearchResult afterWrite =
        recovered
            .readPathOps()
            .search(new MatchAllDocsQuery(), 10, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    assertEquals(1, afterWrite.hits().size(), "rebuilt index must accept new writes under new schema");
    recovered.close();
  }

  @Test
  void schemaMismatchWithoutRebuildPolicyFailsFast() throws Exception {
    Path dataRoot = dataDir();
    Path indexPath = dataRoot.resolve("schema-idx-strict");
    Files.createDirectories(indexPath);

    IndexSchema schemaA = buildSchemaWithTitleType("text");
    RunningRuntime seed = schemaA.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).withFallbackIndexPath(dataRoot).open();
    seed.indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "doc-1",
                    SchemaFields.DOC_UID, "doc-1#0",
                    SchemaFields.CONTENT, "body",
                    "title", "hello")));
    seed.commitOps().commitAndTrack();
    seed.close();

    IndexSchema schemaB = buildSchemaWithTitleType("keyword");
    ResolvedConfig strictCfg = resolvedConfigWith(schemaMismatchPolicyYaml("FAIL_CLOSED"));

    IndexRuntimeIOException ex =
        assertThrows(
            IndexRuntimeIOException.class,
            () ->
                schemaB
                    .atPath(indexPath).withExecutorRegistrations(testLuceneExecutors())
                    .withFallbackIndexPath(dataRoot)
                    .withConfig(strictCfg)
                    .open());
    assertEquals(
        IndexRuntimeIOException.Reason.SCHEMA_MISMATCH,
        ex.reason(),
        "Under FAIL_CLOSED, SCHEMA_MISMATCH must propagate unchanged");
    assertEquals(
        0,
        listSiblingBackups(indexPath, "schema-idx-strict.bak-").size(),
        "no backup must be created under FAIL_CLOSED policy");
  }

  // ==========================================================================
  // C3 — body corruption (628 Stage A+B): detect → recover → rebuild-pending marker
  // ==========================================================================

  @Test
  void bodyCorruptionRecoversAndDropsRebuildMarker() throws Exception {
    Path dataRoot = dataDir();
    Path indexPath = dataRoot.resolve("body-corrupt-idx");
    Files.createDirectories(indexPath);

    IndexSchema schema = buildSchemaWithDim(4);
    RunningRuntime seed = schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).withFallbackIndexPath(dataRoot).open();
    for (int i = 0; i < 50; i++) {
      seed.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID, "doc-" + i,
                      SchemaFields.DOC_UID, "doc-" + i + "#0",
                      SchemaFields.CONTENT, "indexable body content number " + i)));
    }
    seed.commitOps().commitAndTrack();
    seed.close();

    corruptDataFileBody(indexPath);

    // FULL integrity verification detects the silent body bit-rot (CORRUPT_INDEX); auto_recovery backs
    // up the damaged index and rebuilds empty, then drops a rebuild-pending marker so the orchestration
    // layer rebuilds from the source files on disk (the 628 G3 join).
    ResolvedConfig cfg =
        resolvedConfigWith("index:\n  integrity_check: FULL\n  auto_recovery: true\n");
    RunningRuntime recovered =
        schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).withFallbackIndexPath(dataRoot).withConfig(cfg).open();

    SearchResult result =
        recovered
            .readPathOps()
            .search(new MatchAllDocsQuery(), 10, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    assertEquals(0, result.hits().size(), "recovered index must be empty");
    assertEquals(
        1,
        listSiblingBackups(indexPath, "body-corrupt-idx.bak-").size(),
        "corruption recovery must back up the damaged index (never delete)");
    assertTrue(
        IndexRecoveryMarker.exists(indexPath),
        "recovery-to-empty must drop a rebuild-pending marker so the worker rebuilds from source (G3)");
    recovered.close();
  }

  @Test
  void deferredReadOnlyOpenOfCorruptIndexRecovers() throws Exception {
    // Reproduces the live-only bug: the worker opens the active index in DEFERRED (read-only-first)
    // mode, and corruption recovery re-opened read-only on a directory the backup had just emptied —
    // which throws "no index exists". The fix materializes a fresh empty commit before the read-only
    // reopen. Unit tests using the read-WRITE open() path missed this.
    Path dataRoot = dataDir();
    Path indexPath = dataRoot.resolve("deferred-corrupt-idx");
    Files.createDirectories(indexPath);

    IndexSchema schema = buildSchemaWithDim(4);
    RunningRuntime seed = schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).withFallbackIndexPath(dataRoot).open();
    seed.indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "doc-1",
                    SchemaFields.DOC_UID, "doc-1#0",
                    SchemaFields.CONTENT, "before-corruption")));
    seed.commitOps().commitAndTrack();
    seed.close();

    corruptSegmentsFile(indexPath);

    ResolvedConfig cfg = resolvedConfigWith(autoRecoveryYaml(true));
    DeferredRuntime recovered =
        schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).withFallbackIndexPath(dataRoot).withConfig(cfg).openDeferred();

    assertNotNull(
        recovered, "deferred (read-only-first) open of a corrupt index must recover, not fail to start");
    assertEquals(
        1,
        listSiblingBackups(indexPath, "deferred-corrupt-idx.bak-").size(),
        "deferred-mode recovery must back up the damaged index");
    assertTrue(
        IndexRecoveryMarker.exists(indexPath),
        "deferred-mode recovery must drop the rebuild-pending marker (G3)");
    recovered.close();
  }

  @Test
  void cleanCloseWritesMarkerAndTheWriterOpenConsumesIt() throws Exception {
    // tempdoc 628 Gap 1: the clean-shutdown marker is what gates the STRUCTURAL→FULL escalation. A
    // graceful writable close must write it, and the next WRITER open must consume it (so a crash
    // THIS session is detectable next time). (FULL's body-bit-rot detection itself is covered by
    // IndexIntegrityCheckTest.)
    //
    // Tempdoc 915 O14 corrected which open does the consuming. This case asserted that ANY open
    // did, including a read-only one — and that is the defect: a read-only open takes no writer, so
    // it cannot leave the index unclean, and clearing a marker it will never re-write made every
    // later read-only boot report a crash that had not happened. The read-only half is now pinned
    // the other way in CleanShutdownMarkerLifecycleTest.
    Path dataRoot = dataDir();
    Path indexPath = dataRoot.resolve("marker-lifecycle-idx");
    Files.createDirectories(indexPath);

    IndexSchema schema = buildSchemaWithDim(4);
    RunningRuntime rw = schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).withFallbackIndexPath(dataRoot).open();
    rw.indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "doc-1",
                    SchemaFields.DOC_UID, "doc-1#0",
                    SchemaFields.CONTENT, "content")));
    rw.commitOps().commitAndTrack();
    rw.close();

    assertTrue(
        Files.exists(CleanShutdownMarker.pathFor(indexPath)),
        "a graceful writable close must write the clean-shutdown marker");

    ReadOnlyRuntime ro = schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).withFallbackIndexPath(dataRoot).openReadOnly();
    ro.close();
    assertTrue(
        Files.exists(CleanShutdownMarker.pathFor(indexPath)),
        "a read-only open reads the marker to decide whether to escalate, and leaves it: it writes"
            + " nothing, so it cannot be the session that dirties the index");

    // A writer CAN die mid-commit, so its open is what invalidates the previous clean shutdown.
    RunningRuntime rw2 = schema.atPath(indexPath).withExecutorRegistrations(testLuceneExecutors()).withFallbackIndexPath(dataRoot).open();
    assertFalse(
        Files.exists(CleanShutdownMarker.pathFor(indexPath)),
        "opening a WRITER must consume (clear) the clean-shutdown marker");
    rw2.close();
  }

  // ==========================================================================
  // Helpers
  // ==========================================================================

  /** Flips a middle byte of the largest non-{@code segments_N} file — body bit-rot the footer CRC catches. */
  private static void corruptDataFileBody(Path indexPath) throws IOException {
    Path target;
    try (Stream<Path> stream = Files.list(indexPath)) {
      target =
          stream
              .filter(p -> !p.getFileName().toString().startsWith("segments_"))
              .max(
                  Comparator.comparingLong(
                      p -> {
                        try {
                          return Files.size(p);
                        } catch (IOException e) {
                          return 0L;
                        }
                      }))
              .orElseThrow(() -> new AssertionError("no data file to corrupt in " + indexPath));
    }
    byte[] bytes = Files.readAllBytes(target);
    int idx = bytes.length / 2;
    bytes[idx] = (byte) (bytes[idx] ^ 0xFF);
    Files.write(target, bytes);
  }

  /**
   * Overwrites the {@code segments_N} commit pointer with garbage bytes so a reopen
   * fails with {@code CorruptIndexException}. Deleting the file would not work —
   * Lucene treats a missing segments_N as "fresh empty index" and silently
   * initializes one. We need a present-but-malformed segments file to trigger
   * the corruption-classified path.
   */
  private static void corruptSegmentsFile(Path indexPath) throws IOException {
    try (Stream<Path> stream = Files.list(indexPath)) {
      Path segmentsFile =
          stream
              .filter(p -> p.getFileName().toString().startsWith("segments_"))
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError("no segments_N file found in " + indexPath + " — seed step failed"));
      Files.write(segmentsFile, new byte[] {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07});
    }
  }

  private static Stream<Path> listSegmentFiles(Path indexPath) throws IOException {
    return Files.list(indexPath)
        .filter(
            p -> {
              String n = p.getFileName().toString();
              return n.endsWith(".cfs") || n.endsWith(".cfe") || n.endsWith(".si") || n.startsWith("segments_");
            });
  }

  private static List<Path> listSiblingBackups(Path indexPath, String prefix) throws IOException {
    Path parent = indexPath.getParent();
    assertNotNull(parent, "indexPath must have a parent");
    try (Stream<Path> stream = Files.list(parent)) {
      return stream
          .filter(p -> p.getFileName().toString().startsWith(prefix))
          .sorted(Comparator.comparing(Path::getFileName))
          .toList();
    }
  }

  /**
   * Builds the YAML fragment used by the corruption tests. Commit metadata is enabled
   * (production default); tempdoc 406 Gap D fixed the parity-guard wiring so corrupted
   * segments now raise {@link IndexRuntimeIOException}{@code (CORRUPT_INDEX)} and the
   * recovery wrapper triggers backup-rebuild as designed.
   */
  private static String autoRecoveryYaml(boolean enabled) {
    return "index:\n  auto_recovery: " + enabled + "\n";
  }

  private static String schemaMismatchPolicyYaml(String policy) {
    return "index:\n  schema_mismatch:\n    policy: " + policy + "\n";
  }

  private static ResolvedConfig resolvedConfigWith(String yaml) throws Exception {
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeEnvRegistry();
    JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(yaml);
    builder.contributeYaml(root);
    return builder.build();
  }

  /**
   * Builds an IndexSchema where the {@code title} field has the requested type.
   * Used for the schema-mismatch test: seed with type=text, reopen with type=keyword.
   */
  private IndexSchema buildSchemaWithTitleType(String titleType) {
    try {
      String json =
          """
          {
            "fields": [
              { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
              { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
              { "id": "title", "type": "%s", "stored": true, "docValues": %s },
              { "id": "content", "type": "text", "stored": true, "docValues": false },
              { "id": "vector", "type": "vector", "stored": false, "docValues": false, "rmwPolicy": "preserve-reread", "vector": { "dimension": 4 } }
            ]
          }
          """
              .formatted(titleType, "keyword".equals(titleType) ? "true" : "false");
      var mapper = new ObjectMapper();
      var fieldMapper = new FieldMapper(mapper.readTree(json));
      return new IndexSchema(
          fieldMapper,
          new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(),
          io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource::new,
          new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(),
          null);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Item 17 helper: delegating {@link IndexOpenGuard} that counts invocations and records
   * which exception types the wrapped guard raised. Lets the recovery test prove the
   * parity guard's Gap D re-classification path was actually exercised (vs the
   * recovery happening via ComponentsFactory's own open path).
   */
  static final class GuardSpy implements IndexOpenGuard {
    final IndexOpenGuard delegate;
    final AtomicInteger invocations = new AtomicInteger();
    final AtomicBoolean observedCorruptionClassification = new AtomicBoolean();
    final List<String> observedExceptions = new ArrayList<>();

    GuardSpy(IndexOpenGuard delegate) {
      this.delegate = delegate;
    }

    @Override
    public void checkOnOpen() {
      invocations.incrementAndGet();
      try {
        delegate.checkOnOpen();
      } catch (IndexRuntimeIOException e) {
        observedExceptions.add(e.getClass().getSimpleName() + "(" + e.reason() + ")");
        if (e.reason() == IndexRuntimeIOException.Reason.CORRUPT_INDEX) {
          observedCorruptionClassification.set(true);
        }
        throw e;
      } catch (RuntimeException e) {
        observedExceptions.add(e.getClass().getSimpleName());
        throw e;
      }
    }
  }
}
