package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.MMapDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 915 §C — what each {@code index.schema_mismatch.policy} value does to a real index whose
 * committed {@code index_fingerprint} does not match what the runtime would produce.
 *
 * <p>This is the branch the policy default flip lands on, so all three values are pinned together.
 * The distinction that matters to a user is destructive vs non-destructive:
 * {@code REBUILD_BACKUP_FIRST} empties the index in place (fine for a developer, wrong for a
 * shipped desktop app), while {@code FAIL_CLOSED} and {@code BLUE_GREEN_MIGRATE} both leave every
 * document where it is and hand the decision upward — which is what lets {@code KnowledgeServer}
 * open Blue read-only and build Green beside it.
 */
final class SchemaMismatchPolicyBranchTest extends LuceneExecutorTestBase {

  @TempDir Path tempDir;

  /** Writes a config that pins the policy, and points {@code justsearch.config} at it. */
  private Path configFor(String policy) throws Exception {
    Path cfg = tempDir.resolve("policy-" + policy + ".yaml");
    Files.writeString(
        cfg,
        """
        index:
          schema_mismatch:
            policy: %s
          vector:
            dimension: 4
        """
            .formatted(policy));
    return cfg;
  }

  private static CommitMetadataSource withFingerprint(String fingerprint) {
    Map<String, Object> base = new SsotCommitMetadataSource().build();
    return () -> {
      Map<String, Object> m = new HashMap<>(base);
      m.put("index_fingerprint", fingerprint);
      return Map.copyOf(m);
    };
  }

  /**
   * The other way an index reaches these branches: not a DIFFERENT fingerprint but NO fingerprint.
   * Every index built before the key existed looks like this, so it is the shape the whole installed
   * base arrives in — and a policy branch that behaved differently for it would be discovered by
   * users, not by tests (tempdoc 915 §C.5a).
   */
  private static CommitMetadataSource withoutFingerprint() {
    Map<String, Object> base = new HashMap<>(new SsotCommitMetadataSource().build());
    base.remove(IndexFingerprint.COMMIT_META_KEY);
    // BOTH keys. Tempdoc 931 §C.5 stamps the canonical inputs beside the digest, and an index
    // carrying those recorded its shape — the guard compares it instead of migrating it. Removing
    // only the digest would model the "committed while a model was unreadable" index, not the
    // pre-upgrade one these branches are about, and this helper's whole claim is the latter.
    base.remove(IndexFingerprint.COMMIT_META_INPUTS_KEY);
    Map<String, Object> frozen = Map.copyOf(base);
    return () -> frozen;
  }

  private static final String STORED = "a".repeat(64);
  private static final String EXPECTED = "b".repeat(64);

  /** Seeds a committed index stamped with {@link #STORED}, and returns its document count. */
  private int seedIndex(Path dir) throws Exception {
    RunningRuntime r =
        IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(4),
                withFingerprint(STORED),
                new JsonSchemaCommitMetadataValidator())
            .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
            .open();
    r.indexingCoordinator()
        .indexSingle(
            new IndexDocument(Map.of(SchemaFields.DOC_ID, "seed", SchemaFields.DOC_UID, "seed#0")));
    r.commitOps().commitAndTrack(CommitReason.DRAIN);
    r.close();
    return docCount(dir);
  }

  /** Seeds a committed index carrying NO {@code index_fingerprint}, and returns its doc count. */
  private int seedLegacyIndex(Path dir) throws Exception {
    RunningRuntime r =
        IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(4),
                withoutFingerprint(),
                new JsonSchemaCommitMetadataValidator())
            .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
            .open();
    r.indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(SchemaFields.DOC_ID, "legacy", SchemaFields.DOC_UID, "legacy#0")));
    r.commitOps().commitAndTrack(CommitReason.DRAIN);
    r.close();
    return docCount(dir);
  }

  private static int docCount(Path dir) throws Exception {
    try (var d = new MMapDirectory(dir);
        DirectoryReader reader = DirectoryReader.open(d)) {
      return reader.numDocs();
    }
  }

  private void withPolicy(String policy, ThrowingRunnable body) throws Exception {
    String previousConfig = System.getProperty("justsearch.config");
    String previousBase = System.getProperty("justsearch.index.base_path");
    System.setProperty("justsearch.config", configFor(policy).toString());
    // REBUILD_BACKUP_FIRST refuses to move a directory that is not under the configured index base
    // path, so the base has to be real for the destructive branch to be exercised at all.
    System.setProperty("justsearch.index.base_path", tempDir.toAbsolutePath().toString());
    try {
      body.run();
    } finally {
      restore("justsearch.config", previousConfig);
      restore("justsearch.index.base_path", previousBase);
    }
  }

  private static void restore(String key, String previous) {
    if (previous == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, previous);
    }
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  @Test
  void blueGreenMigratePropagatesTheMismatchAndKeepsEveryDocument() throws Exception {
    Path dir = tempDir.resolve("blue-green");
    Files.createDirectories(dir);
    int seeded = seedIndex(dir);
    assertEquals(1, seeded, "the seeded index has a document to lose");

    withPolicy(
        "BLUE_GREEN_MIGRATE",
        () -> {
          IndexRuntimeIOException e =
              assertThrows(
                  IndexRuntimeIOException.class,
                  () ->
                      IndexSchema.fromCatalog(
                              FieldCatalogDef.forTesting(4),
                              withFingerprint(EXPECTED),
                              new JsonSchemaCommitMetadataValidator())
                          .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
                          .open());
          assertEquals(
              IndexRuntimeIOException.Reason.SCHEMA_MISMATCH,
              e.reason(),
              "the mismatch must reach KnowledgeServer as SCHEMA_MISMATCH — that is what starts"
                  + " blue/green; swallowing it here would silently skip the migration");
          assertEquals(
              seeded,
              docCount(dir),
              "Blue must be intact for the read-only reopen that serves search during the rebuild");
        });
  }

  @Test
  void failClosedRefusesAndKeepsEveryDocument() throws Exception {
    Path dir = tempDir.resolve("fail-closed");
    Files.createDirectories(dir);
    int seeded = seedIndex(dir);

    withPolicy(
        "FAIL_CLOSED",
        () -> {
          IndexRuntimeIOException e =
              assertThrows(
                  IndexRuntimeIOException.class,
                  () ->
                      IndexSchema.fromCatalog(
                              FieldCatalogDef.forTesting(4),
                              withFingerprint(EXPECTED),
                              new JsonSchemaCommitMetadataValidator())
                          .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
                          .open());
          assertEquals(IndexRuntimeIOException.Reason.SCHEMA_MISMATCH, e.reason());
          assertEquals(
              seeded, docCount(dir), "FAIL_CLOSED must never touch the documents it refuses to open");
        });
  }

  /**
   * The dev default. Destructive by design — and the reason it is not the production default: it
   * backs the old directory up and rebuilds empty, so the user's search returns nothing until a
   * full re-ingest finishes.
   */
  @Test
  void rebuildBackupFirstEmptiesTheIndexInPlaceAfterBackingItUp() throws Exception {
    Path dir = tempDir.resolve("rebuild-backup");
    Files.createDirectories(dir);
    assertEquals(1, seedIndex(dir));

    withPolicy(
        "REBUILD_BACKUP_FIRST",
        () -> {
          try (RunningRuntime r =
              IndexSchema.fromCatalog(
                      FieldCatalogDef.forTesting(4),
                      withFingerprint(EXPECTED),
                      new JsonSchemaCommitMetadataValidator())
                  .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
                  .open()) {
            assertEquals(
                0,
                r.indexCountOps().docCount(),
                "REBUILD_BACKUP_FIRST rebuilds empty — this is the destructive branch the prod"
                    + " default now avoids");
          }
          try (var siblings = Files.list(dir.getParent())) {
            assertTrue(
                siblings.anyMatch(
                    p -> p.getFileName().toString().startsWith(dir.getFileName() + ".bak-")),
                "the old index must be moved aside to a .bak- sibling, not deleted, before the"
                    + " empty rebuild replaces it");
          }
        });
  }

  @Test
  void aLegacyIndexUnderBlueGreenMigrateReachesSchemaMismatchAndKeepsEveryDocument()
      throws Exception {
    Path dir = tempDir.resolve("legacy-blue-green");
    Files.createDirectories(dir);
    int seeded = seedLegacyIndex(dir);
    assertEquals(1, seeded, "the legacy index has a document to lose");

    withPolicy(
        "BLUE_GREEN_MIGRATE",
        () -> {
          IndexRuntimeIOException e =
              assertThrows(
                  IndexRuntimeIOException.class,
                  () ->
                      IndexSchema.fromCatalog(
                              FieldCatalogDef.forTesting(4),
                              withFingerprint(EXPECTED),
                              new JsonSchemaCommitMetadataValidator())
                          .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
                          .open());
          assertEquals(
              IndexRuntimeIOException.Reason.SCHEMA_MISMATCH,
              e.reason(),
              "an index with no recorded shape must take the same route as one with a different"
                  + " shape — this is the upgrade path the entire installed base walks");
          assertEquals(
              seeded, docCount(dir), "Blue must survive the migration that the absence triggers");
        });
  }

  @Test
  void aLegacyIndexUnderFailClosedRefusesAndKeepsEveryDocument() throws Exception {
    Path dir = tempDir.resolve("legacy-fail-closed");
    Files.createDirectories(dir);
    int seeded = seedLegacyIndex(dir);

    withPolicy(
        "FAIL_CLOSED",
        () -> {
          IndexRuntimeIOException e =
              assertThrows(
                  IndexRuntimeIOException.class,
                  () ->
                      IndexSchema.fromCatalog(
                              FieldCatalogDef.forTesting(4),
                              withFingerprint(EXPECTED),
                              new JsonSchemaCommitMetadataValidator())
                          .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
                          .open());
          assertEquals(IndexRuntimeIOException.Reason.SCHEMA_MISMATCH, e.reason());
          assertEquals(seeded, docCount(dir), "refusing must not cost the user a document");
        });
  }

  /**
   * The destructive branch, reached by an absence rather than a difference. Pinned because this is
   * precisely the combination that would hurt most if it were ever made the production default: a
   * legacy index is what every existing install has, and this branch empties it.
   */
  @Test
  void aLegacyIndexUnderRebuildBackupFirstIsEmptiedInPlaceAfterBackingItUp() throws Exception {
    Path dir = tempDir.resolve("legacy-rebuild-backup");
    Files.createDirectories(dir);
    assertEquals(1, seedLegacyIndex(dir));

    withPolicy(
        "REBUILD_BACKUP_FIRST",
        () -> {
          try (RunningRuntime r =
              IndexSchema.fromCatalog(
                      FieldCatalogDef.forTesting(4),
                      withFingerprint(EXPECTED),
                      new JsonSchemaCommitMetadataValidator())
                  .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
                  .open()) {
            assertEquals(0, r.indexCountOps().docCount());
          }
          try (var siblings = Files.list(dir.getParent())) {
            assertTrue(
                siblings.anyMatch(
                    p -> p.getFileName().toString().startsWith(dir.getFileName() + ".bak-")),
                "even the destructive branch must move the old index aside, not delete it");
          }
        });
  }
}
