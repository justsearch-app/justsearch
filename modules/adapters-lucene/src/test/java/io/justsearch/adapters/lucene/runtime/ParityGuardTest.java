package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import io.justsearch.indexing.runtime.CommitMetadataValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParityGuardTest extends LuceneExecutorTestBase {
  static class GoodMeta implements CommitMetadataSource {
    @Override public Map<String, Object> build() { return new SsotCommitMetadataSource().build(); }
  }

  @Test
  void parityGuardAllowsWritesWhenMetadataMatches() throws Exception {
    Path dir = Files.createTempDirectory("lucene-parity-ok");
    CommitMetadataValidator validator = new JsonSchemaCommitMetadataValidator();

    var goodMeta = new GoodMeta();
    var r1 = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), goodMeta, validator).atPath(dir).withExecutorRegistrations(testLuceneExecutors()).open();
    r1.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(SchemaFields.DOC_ID, "parity-3", SchemaFields.DOC_UID, "parity-3#0")));
    r1.commitOps().commitAndTrack();
    r1.close();

    var r2 = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), goodMeta, validator).atPath(dir).withExecutorRegistrations(testLuceneExecutors()).open();
    // Should not throw
    r2.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(SchemaFields.DOC_ID, "parity-4", SchemaFields.DOC_UID, "parity-4#0")));
    r2.commitOps().commitAndTrack();
    r2.close();
  }

  @Test
  void parityGuardCatchesBoostsMismatch() throws Exception {
    Path dir = Files.createTempDirectory("lucene-parity-boosts");
    CommitMetadataValidator validator = new JsonSchemaCommitMetadataValidator();

    var base = new SsotCommitMetadataSource().build();
    CommitMetadataSource good = () -> base;
    CommitMetadataSource badBoosts = () -> {
      Map<String, Object> m = new HashMap<>(base);
      m.put("boosts_fp", "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
      return m;
    };

    var r1 = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), good, validator).atPath(dir).withExecutorRegistrations(testLuceneExecutors()).open();
    r1.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(SchemaFields.DOC_ID, "parity-5", SchemaFields.DOC_UID, "parity-5#0")));
    r1.commitOps().commitAndTrack();
    r1.close();

    var e =
        assertThrows(
            IllegalStateException.class,
            () ->
                IndexSchema.fromCatalog(
                        FieldCatalogDef.forTesting(768), badBoosts, validator)
                    .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
                    .open());
    assertTrue(e.getMessage().contains("read-only"));
    assertTrue(
        e.getMessage().contains("metadata") || e.getMessage().contains("mismatch")
            || e.getMessage().contains("boosts_fp"),
        "error should mention metadata mismatch, got: " + e.getMessage());
  }

  @Test
  void parityGuardTriggersRebuildOnFingerprintMismatch() throws Exception {
    // A mismatch on index_fingerprint must surface as SCHEMA_MISMATCH so the recovery path acts on
    // it — under the production default that means blue/green, with Blue still serving reads
    // (tempdoc 915 §C). This is the half of the two-key split that costs a rebuild.
    Path dir = Files.createTempDirectory("lucene-parity-rebuild");
    CommitMetadataValidator validator = new JsonSchemaCommitMetadataValidator();

    // Write a committed index stamped with the real (good) metadata.
    var r1 = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), new GoodMeta(), validator).atPath(dir).withExecutorRegistrations(testLuceneExecutors()).open();
    r1.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(SchemaFields.DOC_ID, "parity-6", SchemaFields.DOC_UID, "parity-6#0")));
    r1.commitOps().commitAndTrack();
    r1.close();

    // Drive the guard directly with an expected fingerprint that differs from what was stored.
    Map<String, Object> expected = new HashMap<>(new SsotCommitMetadataSource().build());
    expected.put(
        "index_fingerprint", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    IndexMetadataParityGuard guard = new IndexMetadataParityGuard(() -> dir, () -> expected);

    var e = assertThrows(IndexRuntimeIOException.class, guard::checkOnOpen);
    assertEquals(
        IndexRuntimeIOException.Reason.SCHEMA_MISMATCH,
        e.reason(),
        "index_fingerprint mismatch must surface as SCHEMA_MISMATCH so recovery rebuilds,"
            + " not read-only");
  }

  /**
   * Tempdoc 915 §C.5a — the one predicate that decides "this index has no recorded shape, and that
   * matters". Both the open-time guard and {@code IndexStatusOps}'s reported compatibility state
   * call it, because two independently written versions of this rule is exactly how a brand-new
   * install gets told to rebuild an index that has nothing in it yet.
   */
  @Test
  void anEmptyIndexWithoutAFingerprintIsNotAMigrationCandidate() {
    assertFalse(
        ParityDiagnostics.isIndexWithoutRecordedFingerprint("", null, 0L),
        "an empty index has no content that could have been written under the wrong shape");
    assertFalse(
        ParityDiagnostics.isIndexWithoutRecordedFingerprint(null, null, 0L),
        "absent and blank are the same absence");
    assertTrue(
        ParityDiagnostics.isIndexWithoutRecordedFingerprint("", null, 1L),
        "an index already holding documents of unrecorded shape needs the one-time rebuild");
    assertFalse(
        ParityDiagnostics.isIndexWithoutRecordedFingerprint("recorded-shape", null, 1L),
        "a recorded shape is compared, not migrated blind");
    assertFalse(
        ParityDiagnostics.isIndexWithoutRecordedFingerprint("", "{\"vector_format\":\"int8_sq\"}", 1L),
        "tempdoc 931 §C.5: a commit made while a model digest was unresolvable recorded its shape"
            + " as inputs. That shape is compared, not charged a full rebuild");
  }

  /**
   * The asymmetry the round-4 review found: the empty-index exclusion was applied only where the
   * STORED side was blank, so a 0-document index carrying a stale fingerprint still took the
   * "changed" branch and would have burned a full blue/green migration rebuilding nothing. It
   * re-stamps instead — {@code CommitOps.setLiveCommitData} replaces the whole user-data map on the
   * next commit.
   */
  @Test
  void anEmptyIndexWithAStaleFingerprintIsRestampedNotMigrated() {
    Map<String, String> stored =
        Map.of(io.justsearch.adapters.lucene.commit.IndexFingerprint.COMMIT_META_KEY, "a".repeat(64));
    Map<String, Object> expected = new SsotCommitMetadataSource().build();

    assertTrue(
        ParityDiagnostics.diff(stored, expected, 0L).isEmpty(),
        "an index with no documents has no content whose shape could be wrong");
    assertTrue(
        ParityDiagnostics.requiresRebuild(ParityDiagnostics.diff(stored, expected, 1L)),
        "and the same stale fingerprint on an index that HOLDS something is still a rebuild —"
            + " otherwise the exclusion would have swallowed the case it exists to allow");
  }

  /**
   * Tempdoc 915 B4. Pre-open inspection answers "does the last commit record this runtime's shape?"
   * — anything that stops it reading the commit leaves that UNANSWERED, which is not the same as
   * answering "no". It used to raise {@code CORRUPT_INDEX} from a call site that sits outside
   * {@code RuntimeSession.openComponentsWithRecovery}, so a corrupt index that used to self-heal at
   * boot killed the Worker instead (and the same throw swallowed the legitimate older-Lucene-major
   * upgrade, whose cause is an {@code IndexFormatTooOldException} raised from exactly here).
   */
  @Test
  void anUnreadableCommitIsNotAMismatchAndIsNotFatal() throws Exception {
    Path dir = Files.createTempDirectory("lucene-parity-corrupt");
    var meta = new GoodMeta();
    var r =
        IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), meta, new JsonSchemaCommitMetadataValidator())
            .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
            .open();
    r.indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(SchemaFields.DOC_ID, "c-1", SchemaFields.DOC_UID, "c-1#0")));
    r.commitOps().commitAndTrack();
    r.close();

    try (var files = Files.list(dir)) {
      Path segments =
          files
              .filter(p -> p.getFileName().toString().startsWith("segments"))
              .findFirst()
              .orElseThrow();
      Files.write(segments, new byte[] {0, 1, 2, 3, 4, 5, 6, 7});
    }

    assertTrue(
        IndexMetadataParityGuard.inspectCommittedParity(dir, meta::build).isEmpty(),
        "a commit that cannot be read yields no diffs — the open that follows classifies the"
            + " corruption and runs the recovery this must not pre-empt");
  }

  /**
   * The counter half of G30. The lazy supplier was introduced because an eager version built the
   * expected metadata on a directory with no commits; {@code CommitMetadataIntegrationTest} pins the
   * fresh case. This pins the other one — on an index that DOES exist the metadata is built exactly
   * once per inspection, not once per parity key.
   */
  @Test
  void theExpectedMetadataIsBuiltOncePerInspectionOnAnExistingIndex() throws Exception {
    Path dir = Files.createTempDirectory("lucene-parity-count");
    var meta = new GoodMeta();
    var r =
        IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), meta, new JsonSchemaCommitMetadataValidator())
            .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
            .open();
    r.indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(SchemaFields.DOC_ID, "n-1", SchemaFields.DOC_UID, "n-1#0")));
    r.commitOps().commitAndTrack();
    r.close();

    java.util.concurrent.atomic.AtomicInteger builds =
        new java.util.concurrent.atomic.AtomicInteger();
    IndexMetadataParityGuard.inspectCommittedParity(
        dir,
        () -> {
          builds.incrementAndGet();
          return meta.build();
        });
    assertEquals(1, builds.get(), "one inspection, one expected-metadata build");

    java.util.concurrent.atomic.AtomicInteger onMissing =
        new java.util.concurrent.atomic.AtomicInteger();
    IndexMetadataParityGuard.inspectCommittedParity(
        dir.resolve("no-such-generation"),
        () -> {
          onMissing.incrementAndGet();
          return meta.build();
        });
    assertEquals(0, onMissing.get(), "and none at all when there is nothing to compare against");
  }
}
