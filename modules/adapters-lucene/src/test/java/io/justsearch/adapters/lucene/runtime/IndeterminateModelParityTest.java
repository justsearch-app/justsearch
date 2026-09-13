/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 931 §C.5 — an unreadable model file must not switch off the checks it has nothing to do
 * with.
 *
 * <p>{@code IndexFingerprint.compute} is all-or-nothing on purpose: a digest that hashed a partial
 * input set would mean two different things depending on which inputs were available. But "I cannot
 * give you one digest" is not "I know nothing". Every input the unresolved model does not touch —
 * the vector dimension, the chunking parameters, the analyzer fingerprint, the Lucene major — is
 * still perfectly knowable, and before this an unreadable NER model file meant a
 * genuinely-different-shape index opened silently for as long as that file stayed unreadable.
 *
 * <p>The WARN text itself is asserted in {@code InvariantSuiteIT} (the only module with logback on
 * the test classpath). What is pinned here is the two inputs that WARN is built from: which branch
 * the guard is in ({@link ParityDiagnostics#determinateInputComparisonAvailable}) and which model
 * inputs it names ({@link IndexFingerprint#indeterminateModelInputs()}).
 */
final class IndeterminateModelParityTest extends LuceneExecutorTestBase {

  @AfterEach
  void resetProcessWideProviders() {
    IndexFingerprint.resetModelFingerprintProviders();
  }

  /** Commits one document stamped with whatever {@code meta} produces, and returns its user data. */
  private Map<String, String> seed(Path dir, CommitMetadataSource meta) throws Exception {
    try (var r =
        IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768), meta, new JsonSchemaCommitMetadataValidator())
            .atPath(dir).withExecutorRegistrations(testLuceneExecutors())
            .open()) {
      r.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(SchemaFields.DOC_ID, "indet-1", SchemaFields.DOC_UID, "indet-1#0")));
      r.commitOps().commitAndTrack();
    }
    return storedUserData(dir);
  }

  private static Map<String, String> storedUserData(Path dir) throws Exception {
    try (var directory = org.apache.lucene.store.FSDirectory.open(dir);
        var reader = org.apache.lucene.index.DirectoryReader.open(directory)) {
      return Map.copyOf(reader.getIndexCommit().getUserData());
    }
  }

  /** An unreadable NER model file, with everything else resolvable exactly as before. */
  private static void installUnreadableNerModel() {
    IndexFingerprint.installModelFingerprintProviders(
        IndexFingerprint.ModelFingerprint::notConfigured,
        IndexFingerprint.ModelFingerprint::notConfigured,
        IndexFingerprint.ModelFingerprint::indeterminate);
  }

  /**
   * (i) The case the old behaviour got wrong. The NER model file cannot be digested, so there is no
   * expected {@code index_fingerprint} — and the vector dimension has changed underneath, which the
   * NER model has nothing to do with. That is a rebuild-requiring mismatch and must be routed as
   * one: the same {@code SCHEMA_MISMATCH}, so the same policy branch, the same blue/green trigger
   * and the same brake apply. No new reason code, because it is not a new fact.
   */
  @Test
  void anIndeterminateNerModelStillCatchesAChangedVectorDimension() throws Exception {
    Path dir = Files.createTempDirectory("parity-indet-dimension");
    seed(dir, () -> new SsotCommitMetadataSource().build());

    installUnreadableNerModel();
    IndexFingerprint.installEffectiveVectorDimension(() -> 1024);
    Map<String, Object> expected = new SsotCommitMetadataSource().build();
    assertFalse(
        expected.containsKey(IndexFingerprint.COMMIT_META_KEY),
        "precondition: the digest is genuinely uncomputable, so the old path would decline");
    assertTrue(
        expected.containsKey(IndexFingerprint.COMMIT_META_INPUTS_KEY),
        "precondition: the inputs are stamped even when the digest cannot be");

    var diffs = IndexMetadataParityGuard.inspectCommittedParity(dir, () -> expected);
    assertEquals(1, diffs.size(), "one shape change, one diff: " + markers(diffs));
    assertEquals(
        IndexFingerprint.COMMIT_META_KEY,
        diffs.getFirst().key(),
        "the fallback must route as the rebuild-requiring key, not as a new one");
    assertTrue(
        diffs.getFirst().marker().contains("fields[vector].vector.dimension"),
        "the diagnostics must name the input that moved, keyed by field id: "
            + diffs.getFirst().marker());
    assertTrue(
        ParityDiagnostics.requiresRebuild(diffs),
        "a determinate-input difference is a rebuild, exactly as a digest mismatch is");

    var e =
        assertThrows(
            IndexRuntimeIOException.class,
            () -> new IndexMetadataParityGuard(() -> dir, () -> expected).checkOnOpen());
    assertEquals(IndexRuntimeIOException.Reason.SCHEMA_MISMATCH, e.reason());
  }

  /**
   * (ii) The counter half. Nothing but the NER model's readability changed, so there is nothing to
   * act on: the index opens, and the guard reports that it checked everything except the model
   * digests rather than pretending it checked nothing — or, worse, everything.
   */
  @Test
  void anIndeterminateNerModelAloneOpensAndNamesTheUnresolvedInput() throws Exception {
    Path dir = Files.createTempDirectory("parity-indet-clean");
    Map<String, String> stored = seed(dir, () -> new SsotCommitMetadataSource().build());

    installUnreadableNerModel();
    Map<String, Object> expected = new SsotCommitMetadataSource().build();

    assertTrue(
        ParityDiagnostics.determinateInputComparisonAvailable(stored, expected),
        "the fallback comparison must actually RUN here — a green that came from skipping the"
            + " comparison would prove nothing");
    assertEquals(
        List.of("ner_model_sha256"),
        IndexFingerprint.indeterminateModelInputs(),
        "and the WARN must be able to name which question went unanswered");
    assertTrue(
        IndexMetadataParityGuard.inspectCommittedParity(dir, () -> expected).isEmpty(),
        "an unreadable model file is not a shape change");
    new IndexMetadataParityGuard(() -> dir, () -> expected).checkOnOpen();
  }

  /**
   * (ii-b) The destructive case the ignore list exists for, and the one (ii) cannot reach. There,
   * the stored side also recorded {@code ner_model_sha256: null}, so the comparison would have
   * agreed with or without the drop. Here the index was committed while the model WAS readable, so
   * the stored rendering carries a real digest and the expected one carries {@code null} — compare
   * those verbatim and a transiently unreadable file costs the user a full rebuild, which is
   * precisely the outcome the tri-state design exists to prevent (`green-masked-destructive`: test
   * the adverse precondition, not only the one the environment happens to satisfy).
   */
  @Test
  void aModelThatBecomesUnreadableIsNotAShapeChange() throws Exception {
    Path dir = Files.createTempDirectory("parity-indet-was-readable");
    IndexFingerprint.installModelFingerprintProviders(
        IndexFingerprint.ModelFingerprint::notConfigured,
        IndexFingerprint.ModelFingerprint::notConfigured,
        () -> IndexFingerprint.ModelFingerprint.present("d".repeat(64)));
    Map<String, String> stored = seed(dir, () -> new SsotCommitMetadataSource().build());
    assertTrue(
        stored.get(IndexFingerprint.COMMIT_META_INPUTS_KEY).contains("d".repeat(64)),
        "precondition: the commit recorded a real NER digest, not a null");

    installUnreadableNerModel();
    Map<String, Object> expected = new SsotCommitMetadataSource().build();

    assertTrue(
        ParityDiagnostics.determinateInputComparisonAvailable(stored, expected),
        "the fallback comparison must run — otherwise this passes by not asking");
    assertTrue(
        IndexMetadataParityGuard.inspectCommittedParity(dir, () -> expected).isEmpty(),
        "the ONLY input that changed is the one this runtime admits it cannot resolve; treating"
            + " that as a difference would rebuild the index because a file was briefly locked");
    new IndexMetadataParityGuard(() -> dir, () -> expected).checkOnOpen();
  }

  /**
   * (iii) A commit written before {@code index_fingerprint_inputs} existed has nothing to compare
   * against, so the runtime is back to declining — today's behaviour, unchanged. The fallback is an
   * addition to what a recorded index can prove, not a new demand on one that recorded less.
   */
  @Test
  void aLegacyCommitWithoutRecordedInputsStillDeclines() throws Exception {
    Path dir = Files.createTempDirectory("parity-indet-legacy");
    Map<String, String> stored =
        seed(
            dir,
            () -> {
              Map<String, Object> m = new HashMap<>(new SsotCommitMetadataSource().build());
              m.remove(IndexFingerprint.COMMIT_META_INPUTS_KEY);
              return Map.copyOf(m);
            });
    assertFalse(
        stored.containsKey(IndexFingerprint.COMMIT_META_INPUTS_KEY),
        "precondition: this commit predates the inputs key");

    installUnreadableNerModel();
    IndexFingerprint.installEffectiveVectorDimension(() -> 1024);
    Map<String, Object> expected = new SsotCommitMetadataSource().build();

    assertFalse(
        ParityDiagnostics.determinateInputComparisonAvailable(stored, expected),
        "with nothing recorded to compare against there is no fallback to run");
    assertTrue(
        IndexMetadataParityGuard.inspectCommittedParity(dir, () -> expected).isEmpty(),
        "and an unanswerable question must not be answered 'no'");
    new IndexMetadataParityGuard(() -> dir, () -> expected).checkOnOpen();
  }

  /**
   * The mirror of (i), and the gap the one-sided first cut left. The index was committed while a
   * model file was unreadable, so it recorded inputs and no digest; this runtime can read
   * everything, so it has a digest. Nothing re-stamps a stored digest until the next commit, so a
   * static index in this state was never compared at all — the same bug-class as §C.5 itself,
   * pointing the other way. It must be compared, and a changed vector dimension caught.
   */
  @Test
  void anIndexCommittedWithoutADigestIsCheckedAgainstItsRecordedInputs() throws Exception {
    Path dir = Files.createTempDirectory("parity-indet-stored-blank");
    installUnreadableNerModel();
    Map<String, String> stored = seed(dir, () -> new SsotCommitMetadataSource().build());
    assertFalse(
        stored.containsKey(IndexFingerprint.COMMIT_META_KEY),
        "precondition: the commit recorded no digest");
    assertTrue(
        stored.containsKey(IndexFingerprint.COMMIT_META_INPUTS_KEY),
        "precondition: but it did record the inputs");

    // A later boot where every model resolves — and the vector dimension has moved underneath.
    IndexFingerprint.resetModelFingerprintProviders();
    IndexFingerprint.installEffectiveVectorDimension(() -> 1024);
    Map<String, Object> expected = new SsotCommitMetadataSource().build();
    assertTrue(
        expected.containsKey(IndexFingerprint.COMMIT_META_KEY),
        "precondition: this runtime CAN compute a digest, so the one-sided condition would have"
            + " declined here");

    var diffs = IndexMetadataParityGuard.inspectCommittedParity(dir, () -> expected);
    assertEquals(1, diffs.size(), "one shape change, one diff: " + markers(diffs));
    assertEquals(IndexFingerprint.COMMIT_META_KEY, diffs.getFirst().key());
    assertTrue(
        diffs.getFirst().marker().contains("fields[vector].vector.dimension"),
        "the diagnostics must name the input that moved: " + diffs.getFirst().marker());
    assertFalse(
        diffs.getFirst().marker().contains(ParityDiagnostics.LEGACY_INDEX_HINT),
        "and must NOT be filed as an index that recorded no shape — it recorded one");

    var e =
        assertThrows(
            IndexRuntimeIOException.class,
            () -> new IndexMetadataParityGuard(() -> dir, () -> expected).checkOnOpen());
    assertEquals(IndexRuntimeIOException.Reason.SCHEMA_MISMATCH, e.reason());
  }

  /**
   * The counter half of the mirror, and the reason the ignore list is a union. The stored side
   * recorded {@code ner_model_sha256: null} — which could mean "indeterminate then" or "not
   * configured then", indistinguishably — and this runtime now reads a real digest. Comparing those
   * verbatim would report a difference that may not exist and rebuild the index for it. Nothing
   * else moved, so nothing is reported, and the index is NOT charged the legacy one-time rebuild
   * either.
   */
  @Test
  void aStoredNullModelDigestIsNotAMismatchAgainstANowReadableOne() throws Exception {
    Path dir = Files.createTempDirectory("parity-indet-stored-null-model");
    installUnreadableNerModel();
    Map<String, String> stored = seed(dir, () -> new SsotCommitMetadataSource().build());
    assertTrue(
        stored.get(IndexFingerprint.COMMIT_META_INPUTS_KEY).contains("\"ner_model_sha256\":null"),
        "precondition: the commit recorded the NER digest as an ambiguous null");

    IndexFingerprint.installModelFingerprintProviders(
        IndexFingerprint.ModelFingerprint::notConfigured,
        IndexFingerprint.ModelFingerprint::notConfigured,
        () -> IndexFingerprint.ModelFingerprint.present("d".repeat(64)));
    Map<String, Object> expected = new SsotCommitMetadataSource().build();

    assertTrue(
        ParityDiagnostics.determinateInputComparisonAvailable(stored, expected),
        "the fallback must run — a green from skipping the comparison would prove nothing");
    assertTrue(
        IndexMetadataParityGuard.inspectCommittedParity(dir, () -> expected).isEmpty(),
        "the only input that moved is one the stored side could not state unambiguously, so it is"
            + " not a shape change — and the absent digest is not a legacy migration either");
    new IndexMetadataParityGuard(() -> dir, () -> expected).checkOnOpen();
  }

  /**
   * (v) The inputs key is the SAME statement as the digest, so a shape change must be reported once.
   * A computable digest that differs is the digest's diff and nothing else — if the inputs had been
   * added to {@code PARITY_KEYS} this would report two.
   */
  @Test
  void aComputableDigestMismatchIsReportedOnceNotTwice() throws Exception {
    Path dir = Files.createTempDirectory("parity-indet-nodouble");
    Map<String, String> stored = seed(dir, () -> new SsotCommitMetadataSource().build());

    // Everything resolvable (so the digest IS computable), but a different physical shape.
    IndexFingerprint.installEffectiveVectorDimension(() -> 1024);
    Map<String, Object> expected = new SsotCommitMetadataSource().build();
    assertTrue(
        expected.containsKey(IndexFingerprint.COMMIT_META_KEY),
        "precondition: this arm exercises the digest path, not the fallback");
    assertFalse(
        ParityDiagnostics.determinateInputComparisonAvailable(stored, expected),
        "with BOTH digests present the digest is the answer — it covers the model inputs the"
            + " fallback has to drop, so running the fallback too would only double-report");

    var diffs = IndexMetadataParityGuard.inspectCommittedParity(dir, () -> expected);
    assertEquals(1, diffs.size(), "one shape change, one diff: " + markers(diffs));
    assertEquals(IndexFingerprint.COMMIT_META_KEY, diffs.getFirst().key());
    assertFalse(
        ParityDiagnostics.PARITY_KEYS.contains(IndexFingerprint.COMMIT_META_INPUTS_KEY),
        "the inputs rendering is not a parity key of its own");
  }

  private static String markers(List<ParityDiagnostics.Diff> diffs) {
    return diffs.stream().map(ParityDiagnostics.Diff::marker).toList().toString();
  }
}
