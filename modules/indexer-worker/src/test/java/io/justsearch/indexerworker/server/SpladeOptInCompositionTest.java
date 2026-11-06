/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.indexerworker.index.IndexGenerationManager.ModelArtifact;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.telemetry.OrtSessionTelemetryEvents;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SpladeOptInCompositionTest {
  @TempDir Path temp;

  @Test void defaultBoundGenerationCreatesNoSparseRoleAcrossRecomposition() throws Exception {
    var selection = selected();
    var cfg = config(Map.of());
    for (int replacement = 0; replacement < 3; replacement++) {
      var projection = EncoderConfigurationProjection.from(cfg, selection);
      var hardware = replacement == 1 ? new HardwareProfile(true, true, 12L * 1024 * 1024 * 1024)
          : HardwareProfile.cpuOnly();
      try (var surface = assertDoesNotThrow(() -> InferenceCompositionRoot.compose(
          projection, hardware, null, temp, () -> true,
          OrtSessionTelemetryEvents.NOOP, selection))) {
        assertFalse(surface.componentObservation().requestedRoles().contains(EncoderRole.SPLADE),
            "S1/S12/S17: an intentionally inactive role is not requested or failed");
        assertFalse(surface.componentObservation().missingRoles().contains(EncoderRole.SPLADE));
        assertTrue(surface.splade().isEmpty());
        assertFalse(surface.handleRoles().containsKey(EncoderRole.SPLADE));
        assertFalse(surface.policies().models().containsKey(EncoderRole.SPLADE));
      }
    }
  }

  @Test void globalVetoDisablesBothSparseLanesEvenWithChunkDemand() throws Exception {
    var projection = EncoderConfigurationProjection.from(config(Map.of(
        "justsearch.splade.enabled", "false", "rag.chunk_splade.enabled", "true")), selected());
    assertFalse(projection.splade().enabled(), "S3/S7: generation binding cannot override the explicit veto");
    assertFalse(projection.splade().isReady());
  }

  @Test void discoveredAssetsWithoutDemandHaveZeroGpuReservation() throws Exception {
    selected();
    var cfg = config(Map.of("justsearch.splade.model_path", temp.toString(),
        "justsearch.splade.gpu_enabled", "true", "justsearch.splade.gpu_mem_mb", "1024"));
    assertEquals(0L, InferenceCompositionRoot.estimateCandidateFootprintBytes(
        cfg, new HardwareProfile(true, true, 12L * 1024 * 1024 * 1024), null, temp),
        "S12/S17: inactive SPLADE must not reserve GPU memory");
  }

  @Test void bgeFailureCannotRequestUnpermittedSeparateSplade() throws Exception {
    var original = selected();
    var selection = GenerationModelSelection.accepted(Map.of(
        "splade", new ModelArtifact(temp.resolve("model.onnx").toString(), original.fingerprint("splade").sha()),
        "embedding", new ModelArtifact(temp.resolve("model.onnx").toString(), original.fingerprint("splade").sha())), "bge-m3", 1024);
    var projection = EncoderConfigurationProjection.from(config(Map.of(
        "justsearch.sparse_model", "bge-m3", "justsearch.bgem3.enabled", "true",
        "justsearch.bgem3.model_path", temp.resolve("missing-bge").toString())), selection);
    // An unavailable BGE model degrades without falling into an unrequested SPLADE role.
    try (var surface = assertDoesNotThrow(() -> InferenceCompositionRoot.compose(
        projection, HardwareProfile.cpuOnly(), null, temp, () -> true,
        OrtSessionTelemetryEvents.NOOP, selection))) {
      assertFalse(surface.componentObservation().requestedRoles().contains(EncoderRole.SPLADE),
          "S10: BGE failure does not manufacture permission for the separate encoder");
      assertTrue(surface.splade().isEmpty());
    }
  }

  @Test void permissionDoesNotChangeSelectedIdentityOrPath() throws Exception {
    var selection = selected();
    var off = EncoderConfigurationProjection.from(config(Map.of()), selection);
    var on = EncoderConfigurationProjection.from(config(Map.of("justsearch.splade.enabled", "true",
        "justsearch.splade.model_path", temp.resolve("wrong-model").toString())), selection);
    assertEquals(temp, off.splade().modelPath());
    assertEquals(temp, on.splade().modelPath());
    assertTrue(on.splade().isReady());
    assertTrue(selection.verify("splade").isPresent());
  }

  @Test void permissionChangeKeepsCommittedGenerationParityAfterReopen() throws Exception {
    var selection = selected();
    var on = config(Map.of("justsearch.splade.enabled", "true"));
    var off = config(Map.of());
    var before = new io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource(
        on, selection.runtimeFingerprintInputs()).build();
    var after = new io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource(
        off, selection.runtimeFingerprintInputs()).build();
    assertNotNull(before.get("index_fingerprint"), "precondition: committed generation has a fingerprint");
    assertEquals(before.get("index_fingerprint"), after.get("index_fingerprint"));
    var storedMetadata = new HashMap<>(before);
    // This separate legacy commit stamp is supplied by CommitOps, not by the SSOT source.
    storedMetadata.put("splade_model_sha256", selection.fingerprint("splade").sha());
    Path index = temp.resolve("index");
    try (var directory = org.apache.lucene.store.FSDirectory.open(index);
        var analyzer = new org.apache.lucene.analysis.standard.StandardAnalyzer();
        var writer = new org.apache.lucene.index.IndexWriter(directory,
            new org.apache.lucene.index.IndexWriterConfig(analyzer))) {
      var document = new org.apache.lucene.document.Document();
      document.add(new org.apache.lucene.document.StoredField("content", "retained content"));
      document.add(new org.apache.lucene.document.StoredField("splade_payload", new byte[] {1, 2, 3}));
      document.add(new org.apache.lucene.document.StoredField("splade_retry_count", 2));
      document.add(new org.apache.lucene.document.StoredField("splade_status", "PENDING"));
      document.add(new org.apache.lucene.document.FeatureField("splade", "retained", 2f));
      writer.addDocument(document);
      writer.setLiveCommitData(storedMetadata.entrySet().stream()
          .map(entry -> Map.entry(entry.getKey(), entry.getValue().toString())).toList());
      writer.commit();
    }
    assertTrue(io.justsearch.adapters.lucene.runtime.IndexMetadataParityGuard
        .inspectCommittedParity(index, () -> after).isEmpty(), "S2: opt-out requires no rebuild");
    try (var directory = org.apache.lucene.store.FSDirectory.open(index);
        var reader = org.apache.lucene.index.DirectoryReader.open(directory)) {
      assertEquals(selection.fingerprint("splade").sha(),
          reader.getIndexCommit().getUserData().get("splade_model_sha256"));
      var document = reader.storedFields().document(0);
      assertEquals("retained content", document.get("content"));
      assertEquals("PENDING", document.get("splade_status"));
      assertEquals(2, document.getField("splade_retry_count").numericValue().intValue());
      assertEquals(3, document.getBinaryValue("splade_payload").length);
      assertEquals(1, new org.apache.lucene.search.IndexSearcher(reader).count(
          new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term("splade", "retained"))));
    }
  }

  private GenerationModelSelection selected() throws Exception {
    Path graph = Files.write(temp.resolve("model.onnx"), new byte[] {58, 0});
    Files.writeString(temp.resolve("tokenizer.json"), "{}");
    Files.writeString(temp.resolve("vocab.txt"), "[UNK]\n");
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(graph)));
    return GenerationModelSelection.accepted(Map.of("splade", new ModelArtifact(graph.toString(), sha)), "splade", 768);
  }

  private ResolvedConfig config(Map<String, String> overrides) {
    var entries = new HashMap<String, String>();
    for (String key : new String[] {"justsearch.ai.embed.enabled", "justsearch.ner.enabled",
        "justsearch.bgem3.enabled", "justsearch.rerank.enabled", "justsearch.citation.scorer.enabled"}) {
      entries.put(key, "false");
    }
    entries.put("justsearch.models.dir", temp.resolve("no-discovery").toString());
    entries.putAll(overrides);
    return TestResolvedConfigHelper.fromEntries(entries);
  }
}
