package io.justsearch.adapters.lucene.commit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ResolvedConfig;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 915 §C — the fingerprint has to read the value the runtime actually uses, from the same
 * place the runtime reads it. Every input that is duplicated or defaulted anywhere is a chance for
 * the commit path and the comparison path to disagree, and a disagreement costs a full reindex.
 */
final class FingerprintInputSourcesTest {

  @Test
  void canonicalInputsAndDigestCarryTheV2ChunkAlgorithm() throws Exception {
    var absent = IndexFingerprint.ModelFingerprint.notConfigured();
    var meta = new SsotCommitMetadataSource(ResolvedConfig.builder().contributeBaseSources().build(),
        new SsotCommitMetadataSource.RuntimeFingerprintInputs(null, absent, absent, absent)).build();
    String inputs = meta.get(IndexFingerprint.COMMIT_META_INPUTS_KEY).toString();
    assertTrue(inputs.contains("\"algorithm_version\":\"v2\""), inputs);
    assertNotNull(meta.get(IndexFingerprint.COMMIT_META_KEY));
    assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(inputs.getBytes(java.nio.charset.StandardCharsets.UTF_8))), meta.get(IndexFingerprint.COMMIT_META_KEY));
    String v1 = inputs.replace("\"algorithm_version\":\"v2\"", "\"algorithm_version\":\"v1\"");
    assertNotEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(v1.getBytes(java.nio.charset.StandardCharsets.UTF_8))), meta.get(IndexFingerprint.COMMIT_META_KEY));
  }

  /**
   * Writing a default out explicitly is a no-op edit. If the fingerprint hashed the raw nullable
   * config it would move, and every user would pay a rebuild for a comment-grade config change.
   * Asserted on the effective accessors, which are the single source the codec also reads.
   */
  @Test
  void anExplicitlyWrittenHnswDefaultIsIndistinguishableFromLeavingItUnset() {
    ResolvedConfig unset =
        ResolvedConfig.builder().contributeBaseSources().build();
    ResolvedConfig explicit =
        ResolvedConfig.builder()
            .contributeBaseSources()
            .putDefault(
                "index.vector.hnsw.m", String.valueOf(ResolvedConfig.Index.DEFAULT_VECTOR_HNSW_M))
            .putDefault(
                "index.vector.hnsw.ef_construction",
                String.valueOf(ResolvedConfig.Index.DEFAULT_VECTOR_HNSW_EF_CONSTRUCTION))
            .build();

    assertEquals(
        unset.index().effectiveVectorHnswM(),
        explicit.index().effectiveVectorHnswM(),
        "unset and explicitly-default HNSW m must be the same effective value");
    assertEquals(
        unset.index().effectiveVectorHnswEfConstruction(),
        explicit.index().effectiveVectorHnswEfConstruction(),
        "unset and explicitly-default ef_construction must be the same effective value");
    assertEquals(ResolvedConfig.Index.DEFAULT_VECTOR_HNSW_M, unset.index().effectiveVectorHnswM());
    assertEquals(
        ResolvedConfig.Index.DEFAULT_VECTOR_HNSW_EF_CONSTRUCTION,
        unset.index().effectiveVectorHnswEfConstruction());
  }

  /**
   * The analysis-library versions are read reflectively-ish from the libraries themselves rather
   * than pinned, so this only asserts they resolve to something usable — a null or empty version
   * would silently make an analyzer upgrade invisible to the fingerprint.
   */
  @Test
  void theAnalysisLibraryVersionsResolve() {
    String lucene = org.apache.lucene.util.Version.LATEST.toString();
    String icu = com.ibm.icu.util.VersionInfo.ICU_VERSION.toString();
    assertNotNull(lucene);
    assertNotNull(icu);
    assertTrue(lucene.matches("\\d+.*"), "Lucene version should start with a digit: " + lucene);
    assertTrue(icu.matches("\\d+.*"), "ICU version should start with a digit: " + icu);
  }

  /**
   * Tempdoc 915 §C.3 — the analysis libraries are hashed at {@code major.minor}, deliberately. A
   * Lucene or ICU PATCH release does not change analysis output by either project's compatibility
   * policy, and hashing it would hand every install a full reindex for a dependency bump nobody
   * asked about. The residual risk is named there: a patch that DID change tokenisation goes
   * undetected.
   */
  @Test
  void aPatchLevelLibraryBumpDoesNotMoveTheFingerprint() {
    assertEquals("10.3", SsotCommitMetadataSource.majorMinor("10.3.1"));
    assertEquals(
        SsotCommitMetadataSource.majorMinor("10.3.1"),
        SsotCommitMetadataSource.majorMinor("10.3.9"),
        "a patch bump must be invisible to the fingerprint");
    assertNotEquals(
        SsotCommitMetadataSource.majorMinor("10.3.1"),
        SsotCommitMetadataSource.majorMinor("10.4.0"),
        "a MINOR bump must still move it — that is the analysis change this input exists to catch");
    assertEquals("77", SsotCommitMetadataSource.majorMinor("77"), "no minor part: keep what we have");
    assertEquals("", SsotCommitMetadataSource.majorMinor(null));
  }

  /**
   * Tempdoc 915 §C — the chunk constants have exactly one owner. They were briefly mirrored into
   * this module with a drift test guarding the copy; a mirror plus a guard is still two values, and
   * the guard only fails after someone has already changed one of them. {@code ChunkSplitter} lives
   * in {@code modules:indexing}, which adapters-lucene depends on, so there is no reason for a copy.
   */
  @Test
  void theChunkFingerprintInputsComeFromTheSplitterNotFromACopy() {
    assertEquals(
        2000,
        io.justsearch.indexing.chunking.ChunkSplitter.CHUNK_THRESHOLD_CHARS,
        "the splitter owns the threshold");
    assertEquals(
        4096,
        io.justsearch.indexing.chunking.ChunkSplitter.CONTENT_PREVIEW_MAX_CHARS,
        "the splitter owns the preview bound");
    for (var f : SsotCommitMetadataSource.class.getDeclaredFields()) {
      assertNotEquals(
          "CHUNK_THRESHOLD_CHARS",
          f.getName(),
          "the mirror is gone: read the splitter's constant, do not re-copy it");
      assertNotEquals(
          "CONTENT_PREVIEW_MAX_CHARS",
          f.getName(),
          "the mirror is gone: read the splitter's constant, do not re-copy it");
    }
  }

}
