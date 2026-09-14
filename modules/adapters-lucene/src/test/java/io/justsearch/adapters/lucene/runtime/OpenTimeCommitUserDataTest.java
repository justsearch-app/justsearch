package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import io.justsearch.indexing.runtime.CommitMetadataValidator;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that {@link RunningRuntime#openTimeCommitUserData()} captures the commit
 * metadata from the index as it was when opened, not from subsequent commits.
 *
 * <p>Regression test for the state-gate bug (tempdoc 384 Item 5): {@code
 * latestCommitUserDataBestEffort()} previously required state=RUNNING, but the open-time capture
 * runs during STARTING. The fix adds STARTING to the allowed states.
 */
class OpenTimeCommitUserDataTest extends LuceneExecutorTestBase {

  private static final String BOGUS_SCHEMA_FP =
      "0000000000000000000000000000000000000000000000000000000000000000";

  private String prevAllowMismatch;

  @BeforeEach
  void setup() {
    prevAllowMismatch = System.getProperty("justsearch.index.parity.allow_mismatch");
    System.setProperty("justsearch.index.parity.allow_mismatch", "true");
  }

  @AfterEach
  void cleanup() {
    if (prevAllowMismatch == null) {
      System.clearProperty("justsearch.index.parity.allow_mismatch");
    } else {
      System.setProperty("justsearch.index.parity.allow_mismatch", prevAllowMismatch);
    }
  }

  @Test
  void openTimeSnapshotPreservesOriginalIndexFingerprint(@TempDir Path dir) throws Exception {
    CommitMetadataValidator validator = new JsonSchemaCommitMetadataValidator();

    // Phase 1: Create an index with a bogus schema fingerprint.
    CommitMetadataSource bogusMeta =
        () -> {
          Map<String, Object> m = new java.util.HashMap<>(new SsotCommitMetadataSource().build());
          m.put("index_fingerprint", BOGUS_SCHEMA_FP);
          return m;
        };
    var first = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), bogusMeta, validator).atPath(dir).withExecutorRegistrations(testLuceneExecutors()).open();
    // start removed (builder.open()  starts);
    first.indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(SchemaFields.DOC_ID, "seed-1", SchemaFields.DOC_UID, "seed-1#0")));
    first.commitOps().commitAndTrack();
    first.close();

    // Phase 2: Re-open the index with the real SSOT metadata source.
    // The open-time snapshot should capture the BOGUS fingerprint from Phase 1.
    // After indexing + committing, the latest commit has the REAL fingerprint.
    CommitMetadataSource realMeta = new SsotCommitMetadataSource();
    var second = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768), realMeta, validator).atPath(dir).withExecutorRegistrations(testLuceneExecutors()).open();
    // start removed (builder.open() starts);

    // Capture the open-time snapshot before any new commits.
    Map<String, String> openTime = second.openTimeCommitUserData();
    assertFalse(openTime.isEmpty(), "Open-time snapshot must not be empty");
    assertEquals(
        BOGUS_SCHEMA_FP,
        openTime.get("index_fingerprint"),
        "Open-time snapshot must contain the original (bogus) schema fingerprint");

    // Now index a doc and commit — this overwrites the stored fingerprint with the real one.
    second
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(SchemaFields.DOC_ID, "new-1", SchemaFields.DOC_UID, "new-1#0")));
    second.commitOps().commitAndTrack();

    // The latest commit now has the real fingerprint (different from bogus).
    Map<String, String> latest = second.latestCommitUserDataBestEffort();
    String latestFp = latest.get("index_fingerprint");
    assertNotEquals(
        BOGUS_SCHEMA_FP,
        latestFp,
        "Latest commit must have the real schema fingerprint, not the bogus one");

    // The open-time snapshot must still have the bogus fingerprint.
    Map<String, String> openTimeAfterCommit = second.openTimeCommitUserData();
    assertEquals(
        BOGUS_SCHEMA_FP,
        openTimeAfterCommit.get("index_fingerprint"),
        "Open-time snapshot must be immutable — still the bogus fingerprint after new commits");

    second.close();
  }
}
