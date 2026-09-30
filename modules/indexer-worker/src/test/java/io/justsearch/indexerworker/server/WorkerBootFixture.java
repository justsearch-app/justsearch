/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Boots a real {@link KnowledgeServer} over a prepared generation layout.
 *
 * <p>Shared because the properties tempdoc 915 needs verified are properties of a BOOT, not of a
 * class: whether a mismatch is detected before the open mode is chosen, whether the Worker still
 * binds a port after the rebuild brake is spent. Every one of them is invisible to a unit test —
 * that is what open item O7 and the §C.8 regression both turned on.
 */
final class WorkerBootFixture {

  private WorkerBootFixture() {}

  /** The catalog the Worker itself loads, so a "matching" seed really does match. */
  static FieldCatalogDef productionCatalog() {
    return new JustSearchConfigurationLoader().loadFieldCatalog();
  }

  /**
   * Commits {@code docs} documents into {@code path}. {@code fingerprintOverride} of {@code null}
   * stamps what this runtime would write (a MATCHING index); a hex string stamps a foreign shape
   * (leaving the canonical inputs as this runtime writes them, so the DIGEST is what disagrees);
   * {@link #NO_FINGERPRINT} stamps no recorded shape at all — neither digest nor inputs — which is
   * what every pre-upgrade index looks like.
   */
  static final String NO_FINGERPRINT = "<<absent>>";

  static void seed(Path path, String fingerprintOverride, int docs) throws Exception {
    Map<String, Object> meta = new HashMap<>(new SsotCommitMetadataSource().build());
    if (NO_FINGERPRINT.equals(fingerprintOverride)) {
      meta.remove(IndexFingerprint.COMMIT_META_KEY);
      // Both keys, or this stops modelling a pre-upgrade index. Tempdoc 931 §C.5 stamps the
      // canonical inputs beside the digest, and an index carrying those HAS a recorded shape — it
      // is compared, not migrated. A fixture that removed only the digest would quietly stop
      // exercising the one-time legacy migration it exists for.
      meta.remove(IndexFingerprint.COMMIT_META_INPUTS_KEY);
    } else if (fingerprintOverride != null) {
      meta.put(IndexFingerprint.COMMIT_META_KEY, fingerprintOverride);
    }
    Map<String, Object> frozen = Map.copyOf(meta);
    try (var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var luceneExecutors = new io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations(executors);
        RunningRuntime r =
        IndexSchema.fromCatalog(
                productionCatalog(), () -> frozen, new JsonSchemaCommitMetadataValidator())
            .atPath(path).withExecutorRegistrations(luceneExecutors)
            .open()) {
      for (int i = 0; i < docs; i++) {
        r.indexingCoordinator()
            .indexSingle(
                new IndexDocument(
                    Map.of(
                        SchemaFields.DOC_ID, "seed-" + i,
                        SchemaFields.DOC_UID, "seed-" + i + "#0",
                        SchemaFields.CONTENT, "seeded document " + i)));
      }
      r.commitOps().commitAndTrack(CommitReason.DRAIN);
    }
  }

  /** Commits one parent document with an explicit durable identity. */
  static void seedDocument(
      Path path,
      String fingerprintOverride,
      String docId,
      String docUid,
      String content)
      throws Exception {
    Map<String, Object> meta = new HashMap<>(new SsotCommitMetadataSource().build());
    if (NO_FINGERPRINT.equals(fingerprintOverride)) {
      meta.remove(IndexFingerprint.COMMIT_META_KEY);
      // Both keys, or this stops modelling a pre-upgrade index. Tempdoc 931 §C.5 stamps the
      // canonical inputs beside the digest, and an index carrying those HAS a recorded shape — it
      // is compared, not migrated. A fixture that removed only the digest would quietly stop
      // exercising the one-time legacy migration it exists for.
      meta.remove(IndexFingerprint.COMMIT_META_INPUTS_KEY);
    } else if (fingerprintOverride != null) {
      meta.put(IndexFingerprint.COMMIT_META_KEY, fingerprintOverride);
    }
    Map<String, Object> frozen = Map.copyOf(meta);
    try (var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var luceneExecutors = new io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations(executors);
        RunningRuntime r =
        IndexSchema.fromCatalog(
                productionCatalog(), () -> frozen, new JsonSchemaCommitMetadataValidator())
            .atPath(path).withExecutorRegistrations(luceneExecutors)
            .open()) {
      r.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID,
                      docId,
                      SchemaFields.DOC_UID,
                      docUid,
                      SchemaFields.PATH,
                      docId,
                      SchemaFields.CONTENT,
                      content)));
      r.commitOps().commitAndTrack(CommitReason.DRAIN);
    }
  }

  /** Publishes a config pinning the data dir, the index base and the mismatch policy. */
  static void publishConfig(Path dataDir, Path indexBase, String policy) {
    publishConfig(dataDir, indexBase, policy, Map.of());
  }

  /** As above, plus any extra resolved keys the case under test needs (e.g. auto-recovery). */
  static void publishConfig(
      Path dataDir, Path indexBase, String policy, Map<String, String> extras) {
    ResolvedConfigBuilder builder =
        new ResolvedConfigBuilder()
            .contributeBaseSources()
            .putDefault("justsearch.data.dir", dataDir.toAbsolutePath().toString())
            .putDefault("justsearch.index.base_path", indexBase.toAbsolutePath().toString())
            // Un-prefixed on purpose (ResolvedConfigBuilder:1544). Getting it wrong is silent: the
            // policy falls back to the dev default and the branch under test never runs.
            .putDefault("index.schema_mismatch.policy", policy);
    extras.forEach(builder::putDefault);
    ConfigStore.setGlobal(new ConfigStore(builder.build()));
  }

  /**
   * Puts the layout into the state a boot that resumes a migration sees: {@code migration_state =
   * MIGRATING} with a Green seeded at {@code greenFingerprint}. Shared because two different
   * properties turn on it — what a FRESH budget does with a mismatched Green, and what a SPENT one
   * does — and re-seeding it per test is how the two drifted onto different catalogs.
   *
   * @return the Green generation id
   */
  static String seedInFlightGreen(Layout layout, String greenFingerprint, int docs)
      throws Exception {
    IndexGenerationManager.State inFlight =
        layout.genManager().startMigration("schema_mismatch");
    String greenId = inFlight.building_generation();
    seed(layout.genManager().resolveGenerationPathStrict(greenId), greenFingerprint, docs);
    return greenId;
  }

  /** The {@code index_fingerprint} this runtime would write — the brake's target key. */
  static String currentFingerprint() {
    return new SsotCommitMetadataSource().build().get(IndexFingerprint.COMMIT_META_KEY).toString();
  }

  static WorkerConfig workerConfig(Path dataDir) {
    ResolvedConfig rc = ConfigStore.global().get();
    return new WorkerConfig(
        "127.0.0.1",
        0,
        30_000L,
        128,
        64 * 1024 * 1024,
        dataDir,
        rc.search().collection(),
        60_000L,
        "0.0.0-test",
        new SsotCommitMetadataSource().build(),
        "test-manifest",
        500L,
        "block");
  }

  /** A data directory with an initialised generation layout. */
  static Layout layout(Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path indexBase = dataDir.resolve("index");
    Files.createDirectories(dataDir);
    IndexGenerationManager genManager = new IndexGenerationManager(indexBase);
    var l = genManager.initializeOrLoad();
    return new Layout(
        dataDir,
        indexBase,
        genManager,
        genManager.resolveGenerationPathStrict(l.state().active_generation()));
  }

  record Layout(
      Path dataDir, Path indexBase, IndexGenerationManager genManager, Path activePath) {}
}
